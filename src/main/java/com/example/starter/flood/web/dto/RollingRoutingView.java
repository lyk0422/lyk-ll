package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 滚动演算结果视图：新版本号、过程线洪量、泄流折算、自动升级与方案追加结果。
 */
public record RollingRoutingView(
        String routingNo,
        String warningNo,
        Integer version,
        String triggerType,
        String prevRoutingNo,
        BigDecimal currentWaterLevel,
        BigDecimal currentStorage,
        BigDecimal availableCapacity,
        BigDecimal inflowVolume,
        BigDecimal releasedVolume,
        String capacityJudgment,
        String dangerJudgment,
        String warningLevel,
        boolean warningEscalated,
        String appendedPlanNo,
        Integer appendedPlanVersion,
        String conflictAdviceNo,
        String inputSnapshot,
        boolean replay,
        LocalDateTime createdAt) {
}
