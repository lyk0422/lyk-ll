package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 统一错误响应体。
 *
 * @param timestamp 错误产生时间（带时区）
 * @param code      业务错误码
 * @param message   错误说明
 */
public record ErrorBody(OffsetDateTime timestamp, String code, String message) {
}
