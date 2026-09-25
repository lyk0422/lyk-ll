package com.example.starter.fermentation.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 双乙酰检测受理结果：本次含量、是否合格（恰 0.10mg/L 合格）及与上次合格采样的间隔（小时）。
 */
public record DiacetylResponse(
        String requestId,
        String batchNo,
        Instant testedAt,
        BigDecimal valueMgL,
        boolean passed,
        Long hoursSincePreviousPassing,
        boolean pairedForCooling,
        boolean late) {
}
