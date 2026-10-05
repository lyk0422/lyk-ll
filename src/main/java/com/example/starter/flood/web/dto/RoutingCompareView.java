package com.example.starter.flood.web.dto;

import java.math.BigDecimal;

/**
 * 演算版本对比视图：两个版本的关键结果与差量（后版减前版）。
 */
public record RoutingCompareView(
        RoutingVersionView from,
        RoutingVersionView to,
        BigDecimal waterLevelDelta,
        BigDecimal storageDelta,
        BigDecimal availableCapacityDelta,
        BigDecimal designFloodVolumeDelta,
        String capacityJudgmentChange,
        String dangerJudgmentChange) {
}
