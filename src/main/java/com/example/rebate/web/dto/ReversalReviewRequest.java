package com.example.rebate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * 冲回单复核请求：由不同于提交人的复核人批准或驳回。
 *
 * @param action APPROVE 批准（占用可冲额并产生追缴明细）/ REJECT 驳回（不占额）
 * @param expectedVersion 期望冲回单版本（PENDING 初版为 1）
 * @param occurredAt 业务精确时刻（UTC，纳入幂等指纹）
 */
public record ReversalReviewRequest(
        @NotBlank String requestId,
        @NotBlank String reviewer,
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String action,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull Instant occurredAt) {
}
