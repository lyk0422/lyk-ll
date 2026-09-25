package com.example.starter.pump.web.dto;

import java.time.LocalDateTime;

/**
 * 液位上送处理结果。
 *
 * @param readingId             液位数据ID；重复去重时返回首次ID
 * @param stationCode           泵站代码
 * @param readingTime           采集时刻
 * @param accepted              是否为新受理（false 表示站点＋时刻重复去重）
 * @param deduplicated          是否命中站点＋时刻去重
 * @param late                  是否为归档后迟到数据（只记迟到事件）
 * @param autoStopTaskId        因恰达目标位/低于安全下限自动生成的停泵任务ID，无则 null
 * @param autoStopReason        TARGET_REACHED / SAFE_LOW，无则 null
 * @param missContactOrderId    本次判定生成的失联派单ID，无则 null
 * @param missContactTriggeredAt 失联判定时刻（末次数据时刻恰满30分钟）
 */
public record LevelIngestResult(
        Long readingId,
        String stationCode,
        LocalDateTime readingTime,
        boolean accepted,
        boolean deduplicated,
        boolean late,
        Long autoStopTaskId,
        String autoStopReason,
        Long missContactOrderId,
        LocalDateTime missContactTriggeredAt
) {
}
