package com.example.starter.error;

/**
 * 业务状态冲突（如重复进行中维修单、已结案再复检），映射 HTTP 409。
 */
public class BusinessConflictException extends RuntimeException {

    public BusinessConflictException(String message) {
        super(message);
    }
}
