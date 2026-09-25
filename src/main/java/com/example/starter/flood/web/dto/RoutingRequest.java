package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 调洪演算请求；全部输入字段进入幂等指纹与快照，保证可复算。
 *
 * @param requestId 幂等键
 * @param pondCode 尾矿库编码
 * @param warningNo 关联处置链预警编号
 * @param currentWaterLevel 当前库水位标高，米
 * @param designFloodVolume 设计洪水量，立方米
 * @param dryBeachLength 当前干滩长度，米，可空
 * @param phreaticDepth 当前浸润线埋深，米，可空
 */
public record RoutingRequest(
        @NotBlank String requestId,
        @NotBlank String pondCode,
        @NotBlank String warningNo,
        @NotNull BigDecimal currentWaterLevel,
        @NotNull @DecimalMin("0.0") BigDecimal designFloodVolume,
        @DecimalMin("0.0") BigDecimal dryBeachLength,
        @DecimalMin("0.0") BigDecimal phreaticDepth) {
}
