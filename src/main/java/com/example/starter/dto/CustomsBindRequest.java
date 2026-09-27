package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 包装子批绑定请求：绑定先预占申报支数，累计不超通关单总数。
 *
 * @param requestId       幂等键
 * @param expectedVersion 通关单期望版本（从 1 开始，成功变更加 1）
 * @param batchNo         绑定子批批号（企业品种须与通关单一致）
 * @param declaredDoses   申报预占支数（正整数）
 */
public record CustomsBindRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotBlank String batchNo,
        @NotNull @Positive Integer declaredDoses
) {
}
