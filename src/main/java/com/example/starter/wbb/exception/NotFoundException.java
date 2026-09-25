package com.example.starter.wbb.exception;

/**
 * 请求资源（机型、航班、舱单版本）不存在：返回 404。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
