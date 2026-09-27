package com.example.starter.pump.domain;

import java.time.LocalDateTime;

/**
 * 工况轨迹：每次成功切换一条。
 *
 * @param id          轨迹ID
 * @param zoneCode    流域分区代码
 * @param candidateId 对应切换候选ID
 * @param fromMode    切换前工况 NORMAL/FORCED
 * @param toMode      切换后工况 NORMAL/FORCED
 * @param zoneVersion 切换后的分区工况版本
 * @param tideTime    触发潮位时刻（UTC）
 * @param switchTime  各站任务生效时刻（Asia/Shanghai）
 * @param createdAt   轨迹记录时刻（Asia/Shanghai）
 */
public record TrajectoryRecord(
        Long id,
        String zoneCode,
        Long candidateId,
        String fromMode,
        String toMode,
        int zoneVersion,
        LocalDateTime tideTime,
        LocalDateTime switchTime,
        LocalDateTime createdAt
) {
}
