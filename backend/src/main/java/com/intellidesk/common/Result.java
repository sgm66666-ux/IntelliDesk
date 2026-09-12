package com.intellidesk.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {
    private final int code;
    private final String message;
    private final T data;
    private final String traceId;

    private Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), "success", data, TraceContext.getTraceId());
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, TraceContext.getTraceId());
    }

    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, TraceContext.getTraceId());
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, TraceContext.getTraceId());
    }

    public static <T> Result<T> error(int code, String message, T data) {
        return new Result<>(code, message, data, TraceContext.getTraceId());
    }
}