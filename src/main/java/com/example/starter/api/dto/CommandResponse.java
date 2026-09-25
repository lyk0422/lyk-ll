package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 降掺指令视图：触发→下发（ISSUED）→执行确认（CONFIRMED）/超时升级（ESCALATED）。
 *
 * @param id              指令 ID
 * @param planId          所属方案 ID
 * @param status          指令状态
 * @param hourStart       触发该指令的超标小时起点
 * @param targetHeatRatio 目标替代率（步长降掺；升级时强制为保底比例）
 * @param issuedAt        下发时刻
 * @param confirmDeadline 确认时限（下发后 30 分钟，恰满未确认自动升级）
 * @param confirmedAt     执行确认时刻
 * @param escalatedAt     升级时刻
 */
public record CommandResponse(
        Long id,
        Long planId,
        String status,
        LocalDateTime hourStart,
        BigDecimal targetHeatRatio,
        LocalDateTime issuedAt,
        LocalDateTime confirmDeadline,
        LocalDateTime confirmedAt,
        LocalDateTime escalatedAt) {
}
