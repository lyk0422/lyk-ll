package com.example.rebate.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 分批冲回提交请求（仅允许对已结案批次内的一张报关单发起）。
 *
 * @param declarationNo 被冲回的报关单号（必须属于该结案批次）
 * @param correctionNo 外部更正编号，同批次同编号最多一单
 * @param reason 冲回原因（合法文本，可含任意分隔符，纳入结构化指纹）
 * @param amountCents 冲回金额（正数整数分）
 * @param submitter 提交人（复核人不得与提交人相同）
 * @param expectedVersion 期望版本；冲回单版本从 1 开始，提交须传 1
 * @param occurredAt 业务精确时刻（UTC，客户端给定，纳入幂等指纹）
 */
public record ReversalSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String correctionNo,
        @NotBlank String reason,
        @NotNull @Min(1) Long amountCents,
        @NotBlank String submitter,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull Instant occurredAt) {
}
