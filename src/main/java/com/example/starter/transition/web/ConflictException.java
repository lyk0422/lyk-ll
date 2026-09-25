package com.example.starter.transition.web;

/**
 * 资源冲突或同键异参重放时抛出，映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
