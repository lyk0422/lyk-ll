package com.example.starter.metro.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 退款双人复核请求：两次调用由两名不同复核人发起，第二次（最终确认）
 * 在同一事务内共同校验两人并占用余额、生成独立负向调整明细。
 *
 * @param refundNo        退款单号
 * @param requestId       幂等键
 * @param reviewer        复核人工号
 * @param reviewTime      复核精确时刻（Asia/Shanghai）
 * @param expectedVersion 退款单期望版本（首人复核传 1，最终确认传 2）
 */
public record RefundConfirmRequest(
        @NotBlank String refundNo,
        @NotBlank String requestId,
        @NotBlank String reviewer,
        @NotNull LocalDateTime reviewTime,
        @Positive @NotNull Integer expectedVersion) {
}
