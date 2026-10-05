package com.intellidesk.document.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import com.intellidesk.common.TraceContext;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long taskId;
    private Long documentId;
    private String messageId;
    private int schemaVersion;
    private String traceId;

    public static DocumentProcessMessage create(Long taskId, Long documentId, String messageId) {
        return DocumentProcessMessage.builder()
                .taskId(taskId)
                .documentId(documentId)
                .messageId(messageId)
                .schemaVersion(1)
                .traceId(TraceContext.normalize(TraceContext.peekTraceId()))
                .build();
    }
}
