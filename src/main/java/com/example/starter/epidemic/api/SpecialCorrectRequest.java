package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 发起归档后“特殊订正”请求（须卡片已归档）。
 *
 * @param requestId     幂等键
 * @param cardNo        卡片编号
 * @param diseaseCode   订正后病种编码（可空表示沿用）
 * @param caseType      订正后病例类型（可空表示沿用）
 * @param diagnosisTime 订正后诊断时刻（UTC，可空表示沿用）
 * @param reason        疫情核实结论/变更原因，非空
 * @param initiator     发起人，非空
 */
public record SpecialCorrectRequest(
        @NotBlank String requestId,
        @NotBlank String cardNo,
        String diseaseCode,
        String caseType,
        Instant diagnosisTime,
        @NotBlank String reason,
        @NotBlank String initiator
) {
}
