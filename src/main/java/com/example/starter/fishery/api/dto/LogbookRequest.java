package com.example.starter.fishery.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 渔捞日志提交请求；按作业时刻（UTC）归属日界，按“船＋日期＋渔区”去重。
 *
 * @param requestId 幂等键
 * @param tripCode 航次业务编号
 * @param zoneCode 渔区编号
 * @param fishingAt 作业时刻（UTC，ISO-8601）
 * @param lines 分鱼种渔获量（千克）
 */
public record LogbookRequest(
        @NotBlank String requestId,
        @NotBlank String tripCode,
        @NotBlank String zoneCode,
        @NotNull Instant fishingAt,
        @NotEmpty @Valid List<SpeciesLine> lines) {

    /** 分鱼种渔获行。 */
    public record SpeciesLine(
            @NotBlank String speciesCode,
            @NotNull @DecimalMin(value = "0.001", message = "数量必须大于0") BigDecimal weightKg) {
    }
}
