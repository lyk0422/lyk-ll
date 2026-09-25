package com.example.starter.greenhouse.dto;

import java.time.Instant;

/**
 * 读数上报结果。deduped=true 表示同分区+采样时刻已存在，按去重语义未重复入账；
 * alertTriggered=true 表示本次上报后出现“连续 3 点偏离”并新触发告警。
 */
public record ReadingResponse(
        Long id,
        String zoneCode,
        Instant sampleTime,
        boolean deduped,
        boolean late,
        boolean alertTriggered,
        Long alertId) {
}
