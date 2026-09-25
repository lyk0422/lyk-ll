package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 观察期体征上报。
 *
 * @param requestId     本次操作的幂等键
 * @param diveRequestId 所属潜次 requestId
 * @param symptomatic   是否出现症状：TRUE 立即启动加压舱预案并冻结结案
 * @param observedAt    体征观测时刻（UTC），为空取服务时钟
 */
public record VitalsRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotNull Boolean symptomatic,
        Instant observedAt) {
}
