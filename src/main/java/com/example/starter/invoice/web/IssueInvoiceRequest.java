package com.example.starter.invoice.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开票请求。requestId 为幂等键，指纹覆盖 requestId 与全部业务字段。
 *
 * @param requestId   幂等请求号
 * @param sellerTaxNo 销方纳税人识别号
 * @param buyerTaxNo  购方纳税人识别号
 * @param invoiceNo   发票号码，可空，空时由服务端生成
 * @param buyerBooked 购方是否已入账，null 视为否
 * @param lines       发票行项目，至少一行
 */
public record IssueInvoiceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String sellerTaxNo,
        @NotBlank @Size(max = 32) String buyerTaxNo,
        @Size(max = 32) String invoiceNo,
        Boolean buyerBooked,
        @NotEmpty List<@Valid @NotNull InvoiceLineRequest> lines) {
}
