package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 途中试验与列尾一致性双人确认请求。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 期望处置版本（旧版 409）
 * @param confirmer1      确认人1（不得与确认人2相同）
 * @param confirmer2      确认人2（不得与确认人1相同）
 */
public record EnRouteConfirmRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String confirmer1,
        @NotBlank String confirmer2) {
}
