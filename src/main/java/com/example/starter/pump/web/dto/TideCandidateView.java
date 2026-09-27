package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 强排切换候选查询视图：固定的触发潮位、门槛快照与全部站闸门目标。
 *
 * @param id                候选序列ID
 * @param zoneCode          流域分区代码
 * @param targetMode        目标工况 FORCED/NORMAL
 * @param status            PENDING/ACTIVATED/SUPERSEDED
 * @param tideLevel         固定的触发潮位（米）
 * @param tideObservedAt    固定的触发潮位观测时刻（UTC）
 * @param thresholdSnapshot 生成时顶托门槛快照（米）
 * @param baseVersion       生成时工况版本
 * @param createdAt         候选生成时刻（Asia/Shanghai）
 * @param activatedAt       启用时刻，未启用为 null
 * @param stations          固定的全部相关站闸门目标
 */
public record TideCandidateView(
        Long id,
        String zoneCode,
        String targetMode,
        String status,
        BigDecimal tideLevel,
        Instant tideObservedAt,
        BigDecimal thresholdSnapshot,
        int baseVersion,
        LocalDateTime createdAt,
        LocalDateTime activatedAt,
        List<StationTarget> stations
) {

    /** 候选站闸门目标。 */
    public record StationTarget(String stationCode, BigDecimal targetOpening) {
    }
}
