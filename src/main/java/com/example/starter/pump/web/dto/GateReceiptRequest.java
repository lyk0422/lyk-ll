package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 闸门合成到位回执上报请求。
 *
 * @param requestId   幂等键
 * @param stationCode 泵站代码
 * @param opening     到位开度（百分比，0-100），须与候选固定目标一致
 */
public record GateReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull @DecimalMin(value = "0.0", message = "开度必须在0-100之间")
        @DecimalMax(value = "100.0", message = "开度必须在0-100之间") BigDecimal opening
) {
}
