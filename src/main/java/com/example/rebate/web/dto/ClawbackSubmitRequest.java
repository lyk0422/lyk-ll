package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 冲回提交请求：仅引用已结案批次（路径）内一张报关单及冻结退税额。
 *
 * @param externalCorrectionNo 唯一外部更正编号；同批次同编号最多一单
 * @param reason 冲回原因
 * @param amountCents 冲回金额，单位：分，正整数
 * @param submittedBy 提交人（复核人必须不同于该人）
 */
public record ClawbackSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String externalCorrectionNo,
        @NotBlank String reason,
        @NotNull @Positive Long amountCents,
        @NotBlank String submittedBy) {
}
