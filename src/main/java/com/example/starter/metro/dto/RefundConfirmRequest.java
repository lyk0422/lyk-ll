package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 退款双人确认请求：两名不同复核人在同一最终确认事务内共同校验。
 *
 * @param requestId       幂等键
 * @param refundNo        退款单号
 * @param reviewer1       第一复核人
 * @param reviewer2       第二复核人（必须与第一复核人不同）
 * @param expectedVersion 退款单期望版本（乐观锁，申请成功后为 1）
 * @param confirmTime     确认精确时刻（Asia/Shanghai），参与结构化指纹
 */
public record RefundConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String refundNo,
        @NotBlank String reviewer1,
        @NotBlank String reviewer2,
        @NotNull @Positive Integer expectedVersion,
        @NotNull LocalDateTime confirmTime) {
}
