package com.example.starter.trajectory.exception;

/**
 * 引用的资源（如规则版本）不存在，对应 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
