package com.example.starter.berth.dto;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param code      业务错误码，如 BERTH_WINDOW_CONFLICT、IDEMPOTENCY_CONFLICT
 * @param message   错误描述
 * @param timestamp 出错时刻（UTC）
 */
public record ErrorResponse(String code, String message, Instant timestamp) {
}
