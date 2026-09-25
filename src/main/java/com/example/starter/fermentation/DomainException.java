package com.example.starter.fermentation;

/**
 * 业务规则冲突异常，由全局异常处理映射为 4xx（404 批次不存在 / 409 同键异参、并发唯一冲突等）。
 */
public class DomainException extends RuntimeException {

    private final int status;

    public DomainException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
