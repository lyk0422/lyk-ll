package com.example.starter.pump.web.dto;

import java.time.LocalDateTime;

/**
 * 潮位上送处理结果。
 *
 * @param readingId    潮位数据ID；重复去重时返回首次ID
 * @param zoneCode     流域分区代码
 * @param readingTime  潮位采集时刻（UTC）
 * @param accepted     是否为新受理（false 表示分区＋时刻重复去重）
 * @param deduplicated 是否命中分区＋时刻去重
 * @param late         是否迟到证据（只留证据，不参与工况判定）
 * @param candidateId  本次越限生成的切换候选ID，无则 null
 * @param direction    候选方向 ENTER_FORCED/EXIT_FORCED，无则 null
 * @param replayed     是否为同键重放返回
 */
public record TideIngestResult(
        Long readingId,
        String zoneCode,
        LocalDateTime readingTime,
        boolean accepted,
        boolean deduplicated,
        boolean late,
        Long candidateId,
        String direction,
        boolean replayed
) {
}
