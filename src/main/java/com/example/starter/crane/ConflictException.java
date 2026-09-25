package com.example.starter.crane;

/**
 * 业务冲突：同 requestId 异参（409）、重复释放等。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
