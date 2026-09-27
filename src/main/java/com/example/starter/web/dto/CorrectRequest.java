package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 历史水下记录更正请求：仅对已关闭潜次另记独立差异，不追改原记录与积分账。
 *
 * @param requestId     本次更正操作的幂等键
 * @param diveRequestId 被更正的已关闭潜次 requestId
 * @param diverCode     被更正记录的潜水员工号
 * @param oldBottomMin  原记录水下分钟数（须与库中原记录一致）
 * @param newBottomMin  更正后水下分钟数（正整数）
 * @param correctedAt   更正受理精确时刻（UTC）；为空取服务时钟
 */
public record CorrectRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotBlank String diverCode,
        @Positive int oldBottomMin,
        @Positive int newBottomMin,
        Instant correctedAt) {
}
