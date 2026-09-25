package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 结案后迟到单证登记请求：仅记录迟到事件，不改动已结案批次。
 *
 * @param requestId     幂等键
 * @param declarationNo 迟到报关单号
 * @param invoiceNo     迟到发票号
 * @param payload       原始报文（可空）
 */
public record LateDocumentRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String invoiceNo,
        String payload
) {
}
