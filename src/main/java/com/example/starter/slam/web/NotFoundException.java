package com.example.starter.slam.web;

/**
 * 请求引用的实体不存在（例如边引用了不存在的关键帧），映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
