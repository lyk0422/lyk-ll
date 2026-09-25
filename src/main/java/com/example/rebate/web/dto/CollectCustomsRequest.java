package com.example.rebate.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 报关单采集请求（合成数据）。
 *
 * @param fobAmount FOB 价，计税依据输入
 * @param fobUsd FOB 美元价，换汇成本分母，须大于 0
 */
public record CollectCustomsRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String productCode,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal fobAmount,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal fobUsd) {
}
