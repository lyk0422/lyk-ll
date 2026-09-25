package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 力矩测量结果。
 *
 * @param late true 表示归档后迟到测量，仅登记迟到事件，不改变工序状态
 */
public record TorqueResult(
        String cardNo,
        String pointCode,
        int attemptNo,
        BigDecimal nominalTorque,
        BigDecimal measuredTorque,
        BigDecimal deviationPct,
        boolean inTolerance,
        Integer reworkNo,
        String cardStatus,
        boolean late,
        Instant measuredAt) {
}
