package com.example.starter.legality.service;

/**
 * 请求引用的资源不存在（如未知会的值勤记录、批次）。映射 HTTP 404。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
