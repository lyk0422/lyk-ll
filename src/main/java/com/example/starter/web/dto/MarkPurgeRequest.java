package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 清除标记请求（两阶段清除第一阶段）。
 *
 * @param cutoffTime 截止时间（UTC）：仅 expires_at 不晚于该时刻视为已到期
 * @param expectedVersion 保全全局预期版本，与请求键指纹一起参与幂等校验
 */
public record MarkPurgeRequest(
        @NotBlank String requestId,
        @NotNull Instant cutoffTime,
        Long expectedVersion
) {
}
