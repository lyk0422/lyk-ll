package com.example.starter.margin.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 新的已确认快照请求（合成行情/账务更新）。比例判定一律取账户最新已确认快照。
 *
 * @param totalAssets      最新总资产，单位元
 * @param totalLiabilities 最新总负债，单位元（正常行情波动场景下可与负债合计一致）
 * @param remark           备注
 */
public record SnapshotRequest(
        @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal totalAssets,
        @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal totalLiabilities,
        String remark
) {
}
