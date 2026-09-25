package com.example.starter.recheck;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 复检申诉请求：当事人对处罚决定申请静态复检，requestId 幂等。
 */
public record RecheckApplyRequest(
        @NotBlank String requestId,
        @NotNull Long penaltyId,
        @Positive int staticWeightKg) {
}
