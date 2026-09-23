package com.example.starter.calibration.service;

/**
 * 请求内容校验失败（错误标定、非法状态转换、范围冲突等），映射 HTTP 422。
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
