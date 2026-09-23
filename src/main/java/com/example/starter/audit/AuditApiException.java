package com.example.starter.audit;

/**
 * 请求语义异常：携带 HTTP 状态码（409 同键异参 / 422 版本缺失或不可恢复等）。
 */
public class AuditApiException extends RuntimeException {

    private final int status;

    public AuditApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
