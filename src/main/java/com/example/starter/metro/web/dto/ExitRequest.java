package com.example.starter.metro.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 出闸（含补登出闸）请求。occurredAt 为 Asia/Shanghai 本地时间。
 */
public record ExitRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "txNo 不能为空") String txNo,
        @NotBlank(message = "ticketId 不能为空") String ticketId,
        @NotBlank(message = "station 不能为空") String station,
        @NotNull(message = "occurredAt 不能为空") LocalDateTime occurredAt) {
}
