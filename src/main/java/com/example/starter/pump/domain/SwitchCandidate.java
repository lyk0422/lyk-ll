package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工况切换候选序列：潮位越限生成，固定潮位版本与全部相关站闸门目标。
 *
 * @param id            切换候选ID
 * @param zoneCode      流域分区代码
 * @param direction     切换方向 ENTER_FORCED 进入强排 / EXIT_FORCED 退出强排
 * @param tideReadingId 触发潮位数据ID
 * @param tideTime      触发潮位时刻（UTC）
 * @param tideLevel     触发潮位（米）
 * @param pinnedVersion 生成时固定的分区工况版本
 * @param status        PENDING 待确认 / CONFIRMED 已确认 / SUPERSEDED 已被取代
 * @param createdAt     候选生成时刻（Asia/Shanghai）
 */
public record SwitchCandidate(
        Long id,
        String zoneCode,
        String direction,
        Long tideReadingId,
        LocalDateTime tideTime,
        BigDecimal tideLevel,
        int pinnedVersion,
        String status,
        LocalDateTime createdAt
) {

    public boolean pending() {
        return "PENDING".equals(status);
    }
}
