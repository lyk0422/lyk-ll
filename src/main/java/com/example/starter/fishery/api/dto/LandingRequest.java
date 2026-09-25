package com.example.starter.fishery.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 进港报告（卸渔）请求。
 *
 * @param requestId 幂等键
 * @param tripCode 航次业务编号
 * @param landedAt 进港时刻（UTC，ISO-8601）
 * @param lines 分鱼种卸渔量（千克）
 * @param deviationExplanation 卸渔量与日志累计量偏差超过 3% 时必填的差异说明
 */
public record LandingRequest(
        @NotBlank String requestId,
        @NotBlank String tripCode,
        @NotNull Instant landedAt,
        @NotEmpty @Valid List<SpeciesLine> lines,
        String deviationExplanation) {

    /** 分鱼种卸渔行。 */
    public record SpeciesLine(
            @NotBlank String speciesCode,
            @NotNull BigDecimal weightKg) {
    }
}
