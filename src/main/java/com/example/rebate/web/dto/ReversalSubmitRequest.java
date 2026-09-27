package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 冲回提交请求：引用已结案批次内一张报关单与冻结退税额。
 *
 * @param amountCents 冲回金额（正整数分）
 * @param correctionNo 唯一外部更正编号（同批次最多一单）
 * @param submittedBy 提交人（不得与复核人为同一人）
 */
public record ReversalSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String correctionNo,
        @NotBlank String reason,
        @NotNull @Positive Long amountCents,
        @NotBlank String submittedBy) {
}
