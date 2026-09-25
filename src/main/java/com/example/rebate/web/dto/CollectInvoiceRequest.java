package com.example.rebate.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 进项发票采集请求（合成数据），按“报关单号＋发票号”去重。
 */
public record CollectInvoiceRequest(
        @NotBlank String requestId,
        @NotBlank String invoiceNo,
        @NotBlank String declarationNo,
        @NotBlank String productCode,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
