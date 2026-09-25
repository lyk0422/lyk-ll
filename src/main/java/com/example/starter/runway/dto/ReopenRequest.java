package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 恢复开放请求。requestId 为幂等标识；恢复开放与航行通告发布在同一事务内完成。
 *
 * @param requestId 幂等请求标识
 * @param reopenedAt 恢复开放时间（UTC），同时作为通告发布时间
 */
public record ReopenRequest(
        @NotBlank String requestId,
        @NotNull Instant reopenedAt) {
}
