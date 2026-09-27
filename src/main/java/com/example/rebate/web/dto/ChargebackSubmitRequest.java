package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 结案后冲回提交请求：引用已结案批次内一张报关单，提交冲回原因、正整数分金额与唯一外部更正编号。
 *
 * @param declarationNo 已结案批次内的报关单号
 * @param correctionNo 唯一外部更正编号（同批次内唯一）
 * @param reason 冲回原因（合法文本，可含分隔符）
 * @param amountCents 冲回金额（正数整数分）
 * @param submitter 提交人（须与复核人不同）
 */
public record ChargebackSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String correctionNo,
        @NotBlank String reason,
        @NotNull @Positive Long amountCents,
        @NotBlank String submitter) {
}
