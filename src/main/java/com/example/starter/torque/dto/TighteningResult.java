package com.example.starter.torque.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单次力矩紧固提交结果。
 *
 * @param attemptNo      本次尝试序号（1 首次，2/3 为第 1/2 次返工）
 * @param withinTolerance 是否落在标称值 ±5% 内（含边界）
 * @param lowerBound     允许下限 N·m（含）
 * @param upperBound     允许上限 N·m（含）
 * @param reworkRequired 本次是否超差需返工
 * @param quarantined    本次是否触发“返工 2 次后仍超差，整批隔离”
 * @param late           是否为归档后迟到测量（仅记迟到事件，不改变状态）
 * @param cardStatus     提交后工序卡状态
 */
public record TighteningResult(
        String cardNo,
        String pointCode,
        int attemptNo,
        BigDecimal measuredTorque,
        BigDecimal lowerBound,
        BigDecimal upperBound,
        boolean withinTolerance,
        boolean reworkRequired,
        boolean quarantined,
        boolean late,
        String cardStatus,
        Instant recordedAt
) {
}
