package com.example.starter.metro.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 进闸请求。occurredAt 为 Asia/Shanghai 本地时间（ISO-8601，如 2026-09-25T08:00:00）。
 */
public record EntryRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "txNo 不能为空") String txNo,
        @NotBlank(message = "ticketId 不能为空") String ticketId,
        @NotBlank(message = "station 不能为空") String station,
        @NotNull(message = "occurredAt 不能为空") LocalDateTime occurredAt) {
}
