package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预降事件复算台账：水位曲线、启停序列、失联派单均可复算。
 *
 * @param pumpdownId       预降事件ID
 * @param zoneCode         流域分区代码
 * @param status           ACTIVE/ARCHIVED
 * @param startedAt        预降开始时刻
 * @param archivedAt       归档时刻，未归档为 null
 * @param levelCurve       按站点、时刻排序的液位曲线
 * @param tasks            按时刻排序的启停序列（含闸门开度）
 * @param missContactOrders 失联派单台账
 */
public record ReplayResult(
        Long pumpdownId,
        String zoneCode,
        String status,
        LocalDateTime startedAt,
        LocalDateTime archivedAt,
        List<LevelPoint> levelCurve,
        List<TaskItem> tasks,
        List<MissContactItem> missContactOrders
) {

    /** 水位曲线单点。 */
    public record LevelPoint(String stationCode, LocalDateTime readingTime, BigDecimal level) {
    }

    /** 启停序列单条，START 时带闸门开度。 */
    public record TaskItem(Long taskId, String stationCode, String action,
                           LocalDateTime taskTime, String reason, BigDecimal gateOpening) {
    }

    /** 失联派单台账单条。 */
    public record MissContactItem(Long id, String stationCode, LocalDateTime lastReadingTime,
                                  LocalDateTime triggeredAt, String status) {
    }
}
