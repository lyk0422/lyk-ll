package com.example.starter.transition.web;

/**
 * 业务对象不存在时抛出，映射为 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
