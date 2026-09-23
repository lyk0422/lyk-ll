package com.example.starter.calibration.service;

/**
 * 并发/状态冲突（乐观锁、范围竞争），映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
