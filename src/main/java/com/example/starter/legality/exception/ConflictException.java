package com.example.starter.legality.exception;

/**
 * 同键异参冲突（HTTP 409）。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
