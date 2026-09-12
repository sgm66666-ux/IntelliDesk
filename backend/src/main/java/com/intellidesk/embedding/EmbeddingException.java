package com.intellidesk.embedding;

import lombok.Getter;

@Getter
public class EmbeddingException extends RuntimeException {

    private final String errorCode;

    public EmbeddingException(String message) {
        super(message);
        this.errorCode = "EMBEDDING_ERROR";
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "EMBEDDING_ERROR";
    }

    public EmbeddingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}