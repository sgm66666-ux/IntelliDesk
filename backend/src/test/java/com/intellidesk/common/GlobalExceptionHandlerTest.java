package com.intellidesk.common;

import com.intellidesk.chat.ChatLlmException;
import com.intellidesk.document.parser.DocumentParseException;
import com.intellidesk.embedding.EmbeddingException;
import com.intellidesk.infrastructure.storage.StorageException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private static final String PRIVATE = "synthetic-password-marker Authorization synthetic-value /tmp/synthetic-internal";
    @AfterEach void clean() { TraceContext.clear(); }
    @Test void unknownErrorDoesNotLeakDetailsAndIncludesTrace() {
        TraceContext.setTraceId("error-trace");
        var result = handler.handleException(new RuntimeException(PRIVATE));
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody().getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
        assertThat(result.getBody().getTraceId()).isEqualTo("error-trace");
    }
    @Test void securityErrorsRemain401And403WithoutDetails() {
        assertThat(handler.handleAuthenticationException(new BadCredentialsException(PRIVATE)).getStatusCode().value()).isEqualTo(401);
        assertThat(handler.handleAccessDeniedException(new AccessDeniedException(PRIVATE)).getStatusCode().value()).isEqualTo(403);
        assertThat(handler.handleAuthenticationException(new BadCredentialsException(PRIVATE)).getBody().getMessage()).doesNotContain(PRIVATE);
    }
    @Test void businessErrorPreservesSafeMessageButNotSensitiveMessage() {
        assertThat(handler.handleBusinessException(new BusinessException(ErrorCode.BAD_REQUEST, "名称不能为空")).getBody().getMessage()).isEqualTo("名称不能为空");
        assertThat(handler.handleBusinessException(new BusinessException(ErrorCode.DOCUMENT_UPLOAD_FAILED, PRIVATE)).getBody().getMessage()).isEqualTo(ErrorCode.DOCUMENT_UPLOAD_FAILED.getMessage());
    }
    @Test void parserAndStorageHaveSafeTypedErrors() {
        assertThat(handler.handleDocumentParseException(new DocumentParseException(PRIVATE,"PARSE_FAILED",false)).getStatusCode().value()).isEqualTo(422);
        assertThat(handler.handleStorageException(new StorageException(PRIVATE,true)).getBody().getMessage()).isEqualTo(ErrorCode.DOCUMENT_UPLOAD_FAILED.getMessage());
    }
    @Test void providerCodesAreMappedWithoutProviderBody() {
        assertThat(handler.handleChatLlmException(new ChatLlmException("CHAT_TIMEOUT", PRIVATE)).getStatusCode().value()).isEqualTo(504);
        assertThat(handler.handleEmbeddingException(new EmbeddingException("EMBEDDING_AUTH_ERROR", PRIVATE)).getBody().getMessage()).isEqualTo(ErrorCode.EMBEDDING_AUTH_ERROR.getMessage());
        assertThat(handler.handleChatLlmException(new ChatLlmException("UNRECOGNIZED", PRIVATE)).getBody().getCode()).isEqualTo(ErrorCode.CHAT_PROVIDER_ERROR.getCode());
    }
    @Test void constraintErrorNeverEchoesRejectedValues() {
        assertThat(handler.handleConstraintViolationException(new ConstraintViolationException(PRIVATE,Set.of())).getBody().getMessage()).isEqualTo(ErrorCode.BAD_REQUEST.getMessage());
    }
}
