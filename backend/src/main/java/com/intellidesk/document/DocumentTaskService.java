package com.intellidesk.document;

import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentTaskService {

    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentIngestionProperties ingestionProperties;

    public DocumentTaskService(DocumentIndexTaskMapper taskMapper,
                               DocumentIngestionProperties ingestionProperties) {
        this.taskMapper = taskMapper;
        this.ingestionProperties = ingestionProperties;
    }

    @Transactional
    public DocumentIndexTask createPendingTask(Long documentId, Long requestedBy) {
        DocumentIndexTask task = new DocumentIndexTask();
        task.setDocumentId(documentId);
        task.setTaskType("PARSE_AND_CHUNK");
        task.setStatus("PENDING");
        task.setAttemptCount(0);
        task.setMaxAttempts(ingestionProperties.getMaxAttempts());
        task.setMessageId(UUID.randomUUID().toString());
        task.setRequestedBy(requestedBy);
        taskMapper.insert(task);
        return task;
    }
}
