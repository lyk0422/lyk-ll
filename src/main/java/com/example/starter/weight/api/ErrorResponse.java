package com.example.starter.weight.api;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param code      错误码
 * @param message   错误描述
 * @param timestamp 发生时间（UTC）
 */
public record ErrorResponse(String code, String message, Instant timestamp) {
}
