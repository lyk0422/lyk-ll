package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 结案请求；出水后观察须恰满 2 小时且无症状。
 *
 * @param requestId     本次操作的幂等键
 * @param diveRequestId 所属潜次 requestId
 * @param closedAt      结案时刻（UTC），为空取服务时钟
 */
public record CloseRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        Instant closedAt) {
}
