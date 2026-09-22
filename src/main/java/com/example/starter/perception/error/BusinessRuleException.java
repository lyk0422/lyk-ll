package com.example.starter.perception.error;

/**
 * 业务规则冲突（非法状态迁移、乱序/重复检测等），映射 HTTP 422。
 */
public class BusinessRuleException extends RuntimeException {

    private final String reason;

    public BusinessRuleException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
