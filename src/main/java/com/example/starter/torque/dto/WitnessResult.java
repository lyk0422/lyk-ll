package com.example.starter.torque.dto;

import java.time.Instant;

/**
 * 见证处理结果。
 *
 * @param eventType REQUESTED/ABSENT/ESCALATED/WITNESSED
 * @param escalated 是否在本次处理时判定等待满 2 小时升级
 * @param waitUntil 若处于等待，升级判定时刻(UTC)，即缺席时刻 + 2 小时（恰满 2 小时判定升级）
 */
public record WitnessResult(
        String cardNo,
        String pointCode,
        String eventType,
        String witness,
        boolean escalated,
        Instant waitUntil,
        Instant eventAt,
        String cardStatus
) {
}
