package com.intellidesk.agent.tool;

public class ToolException extends RuntimeException {

    private final String errorCode;

    public ToolException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ToolException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}