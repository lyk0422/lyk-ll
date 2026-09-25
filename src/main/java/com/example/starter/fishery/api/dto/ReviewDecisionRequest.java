package com.example.starter.fishery.api.dto;

import com.example.starter.fishery.domain.ReviewConclusion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 人工复核结论请求；结论为最终口径。
 *
 * @param requestId 幂等键
 * @param conclusion 复核结论：CONFIRM_LANDED 以卸渔量为准 / CONFIRM_LOGGED 以日志量为准 / ADJUSTED 调整量
 * @param note 复核备注
 * @param adjustedLines conclusion=ADJUSTED 时必填的分鱼种最终数量（千克）
 */
public record ReviewDecisionRequest(
        @NotBlank String requestId,
        @NotNull ReviewConclusion conclusion,
        String note,
        @Valid List<AdjustedLine> adjustedLines) {

    /** 复核调整后的分鱼种数量行。 */
    public record AdjustedLine(
            @NotBlank String speciesCode,
            @NotNull BigDecimal weightKg) {
    }
}
