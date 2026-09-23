package com.example.starter.calibration;

/**
 * 标定参数内容或生效范围校验失败（映射 HTTP 422）。
 * 校验失败的参数不能创建为可审批版本。
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
