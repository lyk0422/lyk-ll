package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 强排切换候选序列：固定触发潮位版本与全部相关站闸门目标；
 * 确认前不产生任何启停，确认时原子启用并终结旧序列。
 *
 * @param id               候选序列ID
 * @param requestId        生成候选的潮位上送requestId
 * @param zoneCode         流域分区代码
 * @param targetMode       目标工况 FORCED进入强排/NORMAL退出强排
 * @param tideReadingId    固定的触发潮位数据ID
 * @param tideLevel        固定的触发潮位（米）
 * @param tideObservedAt   固定的触发潮位观测时刻（UTC）
 * @param thresholdSnapshot 生成时顶托门槛快照（米）
 * @param baseVersion      生成时工况版本
 * @param status           PENDING待确认/ACTIVATED已启用/SUPERSEDED已被取代
 * @param createdAt        候选生成时刻（Asia/Shanghai）
 * @param activatedAt      启用时刻，NULL表示未启用
 */
public record TideSwitchCandidate(
        Long id,
        String requestId,
        String zoneCode,
        String targetMode,
        Long tideReadingId,
        BigDecimal tideLevel,
        LocalDateTime tideObservedAt,
        BigDecimal thresholdSnapshot,
        int baseVersion,
        String status,
        LocalDateTime createdAt,
        LocalDateTime activatedAt
) {

    public boolean pending() {
        return "PENDING".equals(status);
    }
}
