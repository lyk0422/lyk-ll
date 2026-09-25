package com.example.starter.web;

/**
 * 请求引用的门站不存在等 404 场景。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
