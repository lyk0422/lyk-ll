package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 切换确认请求。新车到齐后由两个不同合成操作员确认才切换；expectedVersion 为接替单当前版本。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的接替单版本号
 * @param operator        合成操作员
 * @param confirmedAt     UTC精确确认时刻
 */
public record SwitchConfirmRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operator,
        @NotNull Instant confirmedAt) {
}
