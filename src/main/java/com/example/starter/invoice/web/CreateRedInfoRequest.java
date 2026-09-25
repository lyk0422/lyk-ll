package com.example.starter.invoice.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建红字信息表请求。requestId 为幂等键。
 *
 * @param requestId         幂等请求号
 * @param originalInvoiceNo 原蓝字发票号码
 * @param redType           红冲类型：FULL全额/PARTIAL部分
 * @param lines             部分红冲行项目，FULL 时须为空
 */
public record CreateRedInfoRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 32) String originalInvoiceNo,
        @NotNull @Pattern(regexp = "FULL|PARTIAL") String redType,
        List<@Valid @NotNull RedInfoLineRequest> lines) {
}
