package com.example.starter.flood.web.dto;

import java.math.BigDecimal;

/**
 * 滚动演算版本对比视图：任意两版本关键指标并置与差量。
 */
public record RollingCompareView(
        String warningNo,
        Integer fromVersion,
        Integer toVersion,
        RollingVersionView from,
        RollingVersionView to,
        BigDecimal storageDelta,
        BigDecimal availableCapacityDelta,
        BigDecimal inflowRateDelta,
        BigDecimal requiredReleaseDelta,
        String releaseJudgmentChange,
        String dangerJudgmentChange,
        String warningLevelChange,
        String planVersionChange) {
}
