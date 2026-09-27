package com.example.starter.pump.error;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 统一错误响应体。
 *
 * @param currentVersion 版本失配时服务端当前最新版本（其余场景为null），供调用方携带 expectedVersion 重试
 */
public record ApiError(String code, String message, OffsetDateTime timestamp,
                       Integer currentVersion) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null);
    }

    public static ApiError ofVersion(String code, String message, Integer currentVersion) {
        return new ApiError(code, message,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), currentVersion);
    }
}
