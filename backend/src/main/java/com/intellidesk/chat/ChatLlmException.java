package com.intellidesk.chat;

import lombok.Getter;

@Getter
public class ChatLlmException extends RuntimeException {
    private final String errorCode;

    public ChatLlmException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ChatLlmException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}