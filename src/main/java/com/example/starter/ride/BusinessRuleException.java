package com.example.starter.ride;

/**
 * 业务规则异常；携带 HTTP 状态码（如 400 规则冲突、404 设备不存在、409 幂等指纹冲突）。
 */
public class BusinessRuleException extends RuntimeException {

    private final int httpStatus;

    public BusinessRuleException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
