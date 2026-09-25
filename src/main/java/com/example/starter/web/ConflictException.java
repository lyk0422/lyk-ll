package com.example.starter.web;

/**
 * requestId 相同但业务指纹不同（同键异参），映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
