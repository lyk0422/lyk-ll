package com.example.starter.damage.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 修理完工申报：completedAtUtc 用于判定是否在批复有效期内。 */
public record CompleteRepairRequest(
        @NotBlank String requestId,
        @NotBlank String caseNo,
        Long completedAtUtc) {
}
