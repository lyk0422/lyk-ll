package com.example.starter.calibration.service;

/**
 * 资源不存在或状态不允许操作，映射 HTTP 409/404。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
