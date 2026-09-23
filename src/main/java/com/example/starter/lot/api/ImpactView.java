package com.example.starter.lot.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 隔离影响面查询结果。
 *
 * @param lotId          被查询的隔离来源批次
 * @param quarantined    该来源当前是否生效
 * @param affectedLots   因该来源而被隔离的全部批次（含自身与后代）
 */
public record ImpactView(
        String lotId,
        boolean quarantined,
        List<AffectedLot> affectedLots
) {
    /**
     * 单个受影响批次及其当前全部生效隔离来源。
     */
    public record AffectedLot(String lotId, List<String> activeSources) {
    }
}
