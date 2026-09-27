package com.example.starter.pump.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 统一错误响应体。currentVersion 仅在版本失配（409 VERSION_CONFLICT）时携带，
 * 向调用方提供当前最新工况版本，其余错误为 null 且不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, OffsetDateTime timestamp,
                       Integer currentVersion) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null);
    }

    /** 版本失配：拒绝并提供当前最新版本。 */
    public static ApiError ofVersion(String code, String message, Integer currentVersion) {
        return new ApiError(code, message, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                currentVersion);
    }
}
