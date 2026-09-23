package com.example.starter.media.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 可播校验请求：给定合成标识、平台与计划播出时刻，判断是否存在有效授权覆盖。
 */
public record PlayabilityCheckRequest(
        @NotBlank String assetId,
        @NotBlank String region,
        @NotBlank String platform,
        @NotNull Instant at
) {
}
