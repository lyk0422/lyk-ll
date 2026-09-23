package com.example.starter.calibration;

/**
 * 状态转换非法或发布冲突（映射 HTTP 409），例如重复灰度、生效范围重叠。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
