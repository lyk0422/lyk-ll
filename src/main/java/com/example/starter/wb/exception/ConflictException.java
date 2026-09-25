package com.example.starter.wb.exception;

/**
 * 业务规则冲突（HTTP 409）：同键异参、舱单状态不允许该操作、超限未重打等。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
