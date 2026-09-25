package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 出水登记请求，出水后进入 2 小时观察期。
 *
 * @param requestId     本次操作的幂等键
 * @param diveRequestId 所属潜次 requestId
 * @param surfacedAt    出水时刻（UTC）；为空取服务时钟
 */
public record SurfaceRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        Instant surfacedAt) {
}
