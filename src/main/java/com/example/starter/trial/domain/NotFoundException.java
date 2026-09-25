package com.example.starter.trial.domain;

/**
 * 请求引用的试验、中心、受试者等资源不存在（404）。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
