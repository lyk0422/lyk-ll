package com.example.starter.margin.service;

/**
 * 请求的账户/作业等资源不存在（映射 HTTP 404）。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
