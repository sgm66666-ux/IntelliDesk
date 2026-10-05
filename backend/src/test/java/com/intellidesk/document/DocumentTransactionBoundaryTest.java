package com.intellidesk.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.document.chunk.ChunkStrategyRegistry;
import com.intellidesk.document.parser.ParserRegistry;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.*;
import com.intellidesk.infrastructure.storage.ObjectStorageService;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentTransactionBoundaryTest {
    @Test void taskClaimRollsBackWhenDocumentCasFails() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:claim_rollback;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE task_probe(id BIGINT PRIMARY KEY, status VARCHAR(24))");
        jdbc.update("INSERT INTO task_probe VALUES(1,'QUEUED')");
        var tasks = mock(DocumentIndexTaskMapper.class); var documents = mock(DocumentMapper.class);
        when(tasks.update(isNull(),any())).thenAnswer(inv -> jdbc.update("UPDATE task_probe SET status='PROCESSING' WHERE id=1 AND status='QUEUED'"));
        when(documents.update(isNull(),any())).thenReturn(0);
        var service = service(tasks,documents,new DataSourceTransactionManager(ds));
        var task = new DocumentIndexTask(); task.setId(1L); task.setAttemptCount(0); task.setMaxAttempts(3); task.setStatus("QUEUED");
        var doc = new KnowledgeDocument(); doc.setId(2L); doc.setVersion(0);
        assertThat(service.claimTask(task,doc)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM task_probe WHERE id=1",String.class)).isEqualTo("QUEUED");
    }
    @Test void permanentFailureRollsBackWhenDocumentUpdateLosesRace() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:failure_rollback;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE task_probe(id BIGINT PRIMARY KEY, status VARCHAR(24))");
        jdbc.update("INSERT INTO task_probe VALUES(1,'PROCESSING')");
        var tasks = mock(DocumentIndexTaskMapper.class); var documents = mock(DocumentMapper.class);
        when(tasks.update(isNull(),any())).thenAnswer(inv -> jdbc.update("UPDATE task_probe SET status='DEAD' WHERE id=1 AND status='PROCESSING'"));
        when(documents.update(isNull(),any())).thenReturn(0);
        var task = new DocumentIndexTask(); task.setId(1L); var doc = new KnowledgeDocument(); doc.setId(2L);
        service(tasks,documents,new DataSourceTransactionManager(ds)).markPermanentFailure(task,doc,"PARSE_FAILED","synthetic failure",1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_probe WHERE id=1",String.class)).isEqualTo("PROCESSING");
    }
    private DocumentProcessingService service(DocumentIndexTaskMapper tasks,DocumentMapper docs,DataSourceTransactionManager tx) {
        return new DocumentProcessingService(docs,tasks,mock(DocumentChunkMapper.class),mock(KnowledgeBaseMapper.class),
            mock(ObjectStorageService.class),mock(ParserRegistry.class),mock(ChunkStrategyRegistry.class),new DocumentIngestionProperties(),
            new ObjectMapper(),tx,mock(DocumentRetrievalTaskMapper.class),mock(EmbeddingService.class),new RetrievalProperties());
    }
}
