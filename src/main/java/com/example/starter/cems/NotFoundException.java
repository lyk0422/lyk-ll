package com.example.starter.cems;

/**
 * 资源不存在或业务前置条件不满足（HTTP 404）。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
