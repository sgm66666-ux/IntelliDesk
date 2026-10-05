package com.intellidesk.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.stream.Collectors;
import com.intellidesk.document.parser.DocumentParseException;
import com.intellidesk.infrastructure.storage.StorageException;
import com.intellidesk.embedding.EmbeddingException;
import com.intellidesk.chat.ChatLlmException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DocumentParseException.class)
    public ResponseEntity<Result<Void>> handleDocumentParseException(DocumentParseException e) {
        return safeError(ErrorCode.DOCUMENT_PARSE_FAILED);
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Result<Void>> handleStorageException(StorageException e) {
        return safeError(ErrorCode.DOCUMENT_UPLOAD_FAILED);
    }

    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<Result<Void>> handleEmbeddingException(EmbeddingException e) {
        return safeError(providerCode(e.getErrorCode(), "EMBEDDING_", ErrorCode.EMBEDDING_PROVIDER_ERROR));
    }

    @ExceptionHandler(ChatLlmException.class)
    public ResponseEntity<Result<Void>> handleChatLlmException(ChatLlmException e) {
        return safeError(providerCode(e.getErrorCode(), "CHAT_", ErrorCode.CHAT_PROVIDER_ERROR));
    }

    private ErrorCode providerCode(String code, String prefix, ErrorCode fallback) {
        if (code != null && code.startsWith(prefix)) {
            try { return ErrorCode.valueOf(code); }
            catch (IllegalArgumentException ignored) { /* External names are never exposed. */ }
        }
        return fallback;
    }

    private ResponseEntity<Result<Void>> safeError(ErrorCode code) {
        log.warn("Request failed: code={}, traceId={}", code.getCode(), TraceContext.getTraceId());
        return ResponseEntity.status(code.getHttpStatus()).body(Result.error(code));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}", e.getCode());
        HttpStatus httpStatus = findHttpStatusByCode(e.getCode());
        return ResponseEntity.status(httpStatus)
                .body(Result.error(e.getCode(), publicMessage(e.getCode(), e.getMessage())));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrityViolationException(
            DataIntegrityViolationException e) {
        String message = extractSqlMessage(e);
        String lower = message.toLowerCase();

        if (lower.contains("uk_sys_user_username")) {
            log.warn("Username duplicate constraint violation");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.USERNAME_ALREADY_EXISTS));
        }
        if (lower.contains("uk_kb_workspace_name_ci")) {
            log.warn("Knowledge base name duplicate constraint violation");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.KNOWLEDGE_BASE_NAME_ALREADY_EXISTS));
        }
        if (lower.contains("uk_document_kb_checksum")) {
            log.warn("Document checksum duplicate constraint violation");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.DOCUMENT_DUPLICATE));
        }
        if (lower.contains("fk_kb_workspace")) {
            log.warn("Workspace still has knowledge bases");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.WORKSPACE_NOT_EMPTY));
        }

        String sqlState = extractSqlState(e);
        if ("23505".equals(sqlState)) {
            log.warn("Unique constraint violation");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.CONFLICT));
        }
        if ("23503".equals(sqlState)) {
            log.warn("Foreign key constraint violation");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.CONFLICT));
        }

        log.error("Data integrity violation: type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("Upload size exceeded");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.error(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(AuthenticationException e) {
        log.warn("Authentication rejected");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        message = publicMessage(ErrorCode.BAD_REQUEST.getCode(), message);
        log.warn("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e) {
        log.warn("Request body is malformed or has incompatible field types");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("Constraint violation");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("Unhandled exception: type={}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    private HttpStatus findHttpStatusByCode(int code) {
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.getCode() == code) {
                return ec.getHttpStatus();
            }
        }
        return HttpStatus.BAD_REQUEST;
    }

    private String publicMessage(int code, String message) {
        if (message != null && message.length() <= 512 && !message.matches(
                "(?is).*(?:authorization|bearer|password|api.?key|token|secret|cookie|[A-Za-z]:[\\\\/]|/(?:home|etc|var|tmp)/).*")) {
            return message;
        }
        for (ErrorCode error : ErrorCode.values()) {
            if (error.getCode() == code) return error.getMessage();
        }
        return ErrorCode.BAD_REQUEST.getMessage();
    }

    private String extractSqlMessage(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SQLException) {
            String msg = cause.getMessage();
            return msg != null ? msg : "";
        }
        String msg = e.getMessage();
        return msg != null ? msg : "";
    }

    private String extractSqlState(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SQLException) {
                return ((SQLException) cause).getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
