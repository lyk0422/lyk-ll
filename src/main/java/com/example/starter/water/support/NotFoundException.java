package com.example.starter.water.support;

/**
 * 请求资源不存在（取水户、计量点等），映射为 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
