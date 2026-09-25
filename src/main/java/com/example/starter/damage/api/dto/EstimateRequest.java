package com.example.starter.damage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 估价请求：依据当前残损清单生成新估价版本（幂等）。 */
public record EstimateRequest(
        @NotBlank String requestId,
        @NotBlank String caseNo) {
}
