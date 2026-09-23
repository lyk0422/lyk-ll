package com.example.starter.calibration;

/**
 * 请求引用的资源不存在（映射 HTTP 404）。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
