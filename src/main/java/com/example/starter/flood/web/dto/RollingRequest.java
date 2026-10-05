package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 滚动调洪演算请求；全部输入字段进入幂等指纹与源输入快照，保证任一版本可单独复算。
 *
 * @param requestId 幂等键
 * @param currentWaterLevel 当前库水位标高，米
 * @param dryBeachLength 当前干滩长度，米，可空
 * @param phreaticDepth 当前浸润线埋深，米，可空
 */
public record RollingRequest(
        @NotBlank String requestId,
        @NotNull BigDecimal currentWaterLevel,
        @DecimalMin("0.0") BigDecimal dryBeachLength,
        @DecimalMin("0.0") BigDecimal phreaticDepth) {
}
