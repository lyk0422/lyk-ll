package com.example.starter.pump.error;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 统一错误响应体。
 */
public record ApiError(String code, String message, OffsetDateTime timestamp) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}
