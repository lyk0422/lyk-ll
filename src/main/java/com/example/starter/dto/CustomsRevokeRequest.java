package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 通关单撤销请求：释放全部未签发子批预占并阻断其签发，已签发证据保留并记风险事件。
 *
 * @param requestId       幂等键
 * @param expectedVersion 通关单期望版本
 */
public record CustomsRevokeRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion
) {
}
