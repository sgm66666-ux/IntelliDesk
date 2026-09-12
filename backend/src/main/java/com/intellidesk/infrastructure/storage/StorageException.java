package com.intellidesk.infrastructure.storage;

public class StorageException extends Exception {

    private final boolean retryable;

    public StorageException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public StorageException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
