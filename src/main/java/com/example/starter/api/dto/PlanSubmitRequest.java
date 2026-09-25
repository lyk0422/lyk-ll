package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 掺烧方案申报请求（以 requestId 幂等）。
 *
 * @param requestId        申报幂等键
 * @param kilnId           窑编号
 * @param licenseNo        绑定的危废经营许可证号（合成许可库）
 * @param substitutionRate 申报替代燃料热量替代率（0.40 表示 40%，上限恰 40%）
 * @param plannedStart     计划开始时刻（Asia/Shanghai）
 * @param plannedEnd       计划结束时刻
 * @param fuels            燃料明细，至少一项
 */
public record PlanSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String kilnId,
        @NotBlank String licenseNo,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal substitutionRate,
        @NotNull LocalDateTime plannedStart,
        @NotNull LocalDateTime plannedEnd,
        @NotEmpty @Valid List<FuelDto> fuels) {
}
