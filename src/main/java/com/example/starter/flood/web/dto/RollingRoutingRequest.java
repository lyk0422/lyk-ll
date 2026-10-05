package com.example.starter.flood.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;

/**
 * 滚动演算请求：按入库洪水过程线重算调洪结果，恰满滚动周期触发；
 * 全部业务字段进入幂等指纹，源输入快照随版本保留可复算。
 *
 * @param requestId 幂等键
 * @param warningNo 关联处置链预警编号
 * @param hydrograph 入库洪水过程线（首点 minute=0，分钟严格递增）
 * @param dryBeachLength 当前干滩长度，米，可空（空值沿用上一版本）
 * @param phreaticDepth 当前浸润线埋深，米，可空（空值沿用上一版本）
 */
public record RollingRoutingRequest(
        @NotBlank String requestId,
        @NotBlank String warningNo,
        @NotEmpty @Valid List<InflowPoint> hydrograph,
        @DecimalMin("0.0") BigDecimal dryBeachLength,
        @DecimalMin("0.0") BigDecimal phreaticDepth) {
}
