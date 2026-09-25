package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 采集请求：一张报关单与一张进项发票按商品编码配对。
 *
 * @param requestId     幂等键
 * @param declarationNo 报关单号
 * @param invoiceNo     进项发票号
 * @param hsCode        商品编码（报关单与发票关联键）
 * @param fobRmb        FOB 价（人民币元）
 * @param fobUsd        FOB 美元价（美元）
 */
public record CollectDocumentRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String invoiceNo,
        @NotBlank String hsCode,
        @NotNull @Positive BigDecimal fobRmb,
        @NotNull @Positive BigDecimal fobUsd
) {
}
