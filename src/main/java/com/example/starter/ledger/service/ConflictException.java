package com.example.starter.ledger.service;

/**
 * 业务状态冲突（含同键异参、在途约束、重复核销等），映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
