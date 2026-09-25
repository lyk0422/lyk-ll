package com.example.starter.trial.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 应急揭盲确认请求（两步确认中的每一步都用同一个 requestId）。
 *
 * @param requestId 揭盲申请幂等键（重复揭盲返回首次结果）
 * @param trialId   试验编号
 * @param subjectScreenNo 受试者筛选号
 * @param confirmerUserId 本步确认的授权人工号（两名确认人必须不同）
 */
public record UnblindingConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String trialId,
        @NotBlank String subjectScreenNo,
        @NotBlank String confirmerUserId) {
}
