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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus httpStatus = findHttpStatusByCode(e.getCode());
        return ResponseEntity.status(httpStatus)
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrityViolationException(
            DataIntegrityViolationException e) {
        String message = extractSqlMessage(e);
        String lower = message.toLowerCase();

        if (lower.contains("uk_sys_user_username")) {
            log.warn("Username duplicate violation: {}", message);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.USERNAME_ALREADY_EXISTS));
        }
        if (lower.contains("uk_kb_workspace_name_ci")) {
            log.warn("Knowledge base name duplicate violation: {}", message);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.KNOWLEDGE_BASE_NAME_ALREADY_EXISTS));
        }
        if (lower.contains("uk_document_kb_checksum")) {
            log.warn("Document checksum duplicate violation: {}", message);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.DOCUMENT_DUPLICATE));
        }
        if (lower.contains("fk_kb_workspace")) {
            log.warn("Workspace still has knowledge bases: {}", message);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.WORKSPACE_NOT_EMPTY));
        }

        String sqlState = extractSqlState(e);
        if ("23505".equals(sqlState)) {
            log.warn("Unique constraint violation: {}", message);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.CONFLICT));
        }
        if ("23503".equals(sqlState)) {
            log.warn("Foreign key constraint violation: {}", message);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.error(ErrorCode.CONFLICT));
        }

        log.error("Data integrity violation", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("Upload size exceeded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.error(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(AuthenticationException e) {
        log.warn("Authentication exception: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
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
        log.warn("Constraint violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
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
