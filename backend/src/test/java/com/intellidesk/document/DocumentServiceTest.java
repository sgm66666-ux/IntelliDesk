package com.intellidesk.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.document.dto.DocumentUploadResponse;
import com.intellidesk.document.mq.DocumentTaskPublisher;
import com.intellidesk.document.parser.DocumentFormatDetector;
import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import com.intellidesk.infrastructure.config.MinioProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.infrastructure.storage.ObjectStorageService;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.retrieval.indexing.RetrievalCleanupService;
import com.intellidesk.retrieval.indexing.RetrievalTaskService;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceTest {

    @Mock
    private WorkspaceAuthorizationService workspaceAuthorizationService;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentIndexTaskMapper taskMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private DocumentTaskService documentTaskService;
    private final DocumentFormatDetector formatDetector = new DocumentFormatDetector();
    @Mock
    private MinioProperties minioProperties;
    @Mock
    private DocumentIngestionProperties ingestionProperties;
    @Mock
    private DocumentTaskPublisher documentTaskPublisher;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RetrievalCleanupService retrievalCleanupService;
    @Mock
    private RetrievalTaskService retrievalTaskService;
    @Mock
    private RetrievalProperties retrievalProperties;

    private DocumentService documentService;

    @BeforeEach
    void setUp() throws Exception {
        documentService = new DocumentService(
                workspaceAuthorizationService,
                knowledgeBaseService,
                objectStorageService,
                documentMapper,
                taskMapper,
                chunkMapper,
                documentTaskService,
                formatDetector,
                minioProperties,
                ingestionProperties,
                documentTaskPublisher,
                new ObjectMapper(),
                transactionManager,
                retrievalCleanupService,
                retrievalTaskService,
                retrievalProperties
        );

        TransactionTemplate stubTemplate = new TransactionTemplate(transactionManager) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
        Field field = DocumentService.class.getDeclaredField("transactionTemplate");
        field.setAccessible(true);
        field.set(documentService, stubTemplate);

        when(ingestionProperties.getMaxFileSizeBytes()).thenReturn(20 * 1024 * 1024L);
        when(ingestionProperties.getUploadCleanupGraceSeconds()).thenReturn(120);
        when(minioProperties.getBucket()).thenReturn("intellidesk-documents");
        when(documentTaskPublisher.publishMain(any())).thenReturn(
                new DocumentTaskPublisher.PublishResult(true, false, false, null));
    }

    @Test
    void shouldUploadTextFile() throws Exception {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(2L);
        kb.setWorkspaceId(1L);
        kb.setChunkStrategy("FIXED_SIZE");
        kb.setChunkSize(200);
        kb.setChunkOverlap(20);

        when(knowledgeBaseService.getKnowledgeBase(1L, 2L, 3L)).thenReturn(kb);
        when(documentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument doc = inv.getArgument(0);
            doc.setId(100L);
            return 1;
        });
        when(documentMapper.updateById(any(KnowledgeDocument.class))).thenReturn(1);
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(documentMapper.selectById(100L)).thenAnswer(inv -> {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId(100L);
            doc.setStatus("PENDING");
            doc.setKnowledgeBaseId(2L);
            doc.setOriginalFileName("sample.txt");
            doc.setFileSize(100L);
            doc.setChecksumSha256("abc");
            return doc;
        });

        DocumentIndexTask task = new DocumentIndexTask();
        task.setId(200L);
        task.setStatus("PENDING");
        when(documentTaskService.createPendingTask(100L, 3L)).thenReturn(task);

        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.txt", "text/plain",
                "IntelliDesk test content 🚀".getBytes(StandardCharsets.UTF_8));

        DocumentUploadResponse response = documentService.uploadDocument(1L, 2L, 3L, file);

        assertThat(response.getDocumentId()).isEqualTo(100L);
        assertThat(response.getTaskId()).isEqualTo(200L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(objectStorageService).putObject(anyString(), contains("workspaces/1/knowledge-bases/2/documents/100/source.txt"),
                any(ByteArrayInputStream.class), eq("text/plain"), anyLong());
    }

    @Test
    void shouldRejectEmptyFile() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(2L);
        kb.setWorkspaceId(1L);
        when(knowledgeBaseService.getKnowledgeBase(1L, 2L, 3L)).thenReturn(kb);

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> documentService.uploadDocument(1L, 2L, 3L, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_EMPTY.getCode());
    }

    @Test
    void shouldRejectUnsupportedType() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(2L);
        kb.setWorkspaceId(1L);
        when(knowledgeBaseService.getKnowledgeBase(1L, 2L, 3L)).thenReturn(kb);

        MockMultipartFile file = new MockMultipartFile(
                "file", "setup.exe", "application/x-msdownload",
                "binary content".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.uploadDocument(1L, 2L, 3L, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED.getCode());
    }

    @Test
    void shouldRejectDuplicateChecksum() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(2L);
        kb.setWorkspaceId(1L);
        when(knowledgeBaseService.getKnowledgeBase(1L, 2L, 3L)).thenReturn(kb);
        when(documentMapper.selectCount(any())).thenReturn(1L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.txt", "text/plain",
                "duplicate content".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.uploadDocument(1L, 2L, 3L, file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_DUPLICATE.getCode());
    }
}
