package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 演算版本视图：任一历史版本可查，输入快照保留可单独复算。
 */
public record RoutingVersionView(
        String routingNo,
        String warningNo,
        Integer version,
        String triggerType,
        String prevRoutingNo,
        BigDecimal currentWaterLevel,
        BigDecimal currentStorage,
        BigDecimal availableCapacity,
        BigDecimal designFloodVolume,
        String capacityJudgment,
        String dangerJudgment,
        String inflowHydrograph,
        String inputSnapshot,
        String criterion,
        LocalDateTime createdAt) {
}
