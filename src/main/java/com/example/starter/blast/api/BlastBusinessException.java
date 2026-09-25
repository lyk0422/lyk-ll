package com.example.starter.blast.api;

/**
 * 通用业务异常，携带 HTTP 状态码，由全局异常处理器转换为响应。
 */
public class BlastBusinessException extends RuntimeException {

    private final int status;

    public BlastBusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
