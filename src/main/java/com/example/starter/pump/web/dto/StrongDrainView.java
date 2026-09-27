package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 潮汐顶托工况查询视图：潮位轨迹、当前工况、候选/生效序列及工况轨迹。
 *
 * @param zoneCode        流域分区代码
 * @param configured      是否配置潮汐控制（false 时分区保持原预降行为）
 * @param thresholdLevel  顶托门槛潮位（米），未配置为 null
 * @param exitLine        强排退出回落线（米，门槛-0.2），未配置为 null
 * @param regimeVersion   工况配置版本，未配置为 null
 * @param currentMode     当前工况 NORMAL 常规 / STRONG 强排
 * @param activeSequence  当前生效强排序列（无则 null）
 * @param openCandidate   当前待确认候选（无则 null）
 * @param tideReadings    潮位轨迹（按版本升序）
 * @param traces          工况轨迹（按时间升序）
 */
public record StrongDrainView(
        String zoneCode,
        boolean configured,
        BigDecimal thresholdLevel,
        BigDecimal exitLine,
        Integer regimeVersion,
        String currentMode,
        SequenceDetail activeSequence,
        SequenceDetail openCandidate,
        List<TidePoint> tideReadings,
        List<TraceItem> traces
) {

    /** 序列明细：固定潮位版本、方向、状态、闸门目标与回执/任务。 */
    public record SequenceDetail(
            Long id,
            Long tideVersion,
            BigDecimal tideLevel,
            String direction,
            Integer regimeVersion,
            Long expectedActiveSequenceId,
            String state,
            OffsetDateTime taskTime,
            LocalDateTime createdAt,
            List<TargetItem> targets,
            List<ReceiptItem> receipts,
            List<TaskItem> tasks
    ) {
    }

    /** 潮位轨迹点。 */
    public record TidePoint(
            Long id,
            OffsetDateTime tideTime,
            BigDecimal tideLevel,
            LocalDateTime receivedAt
    ) {
    }

    /** 候选固化的单站闸门目标。 */
    public record TargetItem(String stationCode, BigDecimal gateTarget) {
    }

    /** 闸门到位回执。 */
    public record ReceiptItem(String stationCode, BigDecimal opening, LocalDateTime receivedAt) {
    }

    /** 强排各站启停任务。 */
    public record TaskItem(
            Long id,
            String stationCode,
            String action,
            OffsetDateTime taskTime,
            String reason,
            String taskState
    ) {
    }

    /** 工况轨迹条目。 */
    public record TraceItem(
            Long id,
            String zoneCode,
            Long tideVersion,
            String eventType,
            String fromMode,
            String toMode,
            Long sequenceId,
            String detail,
            LocalDateTime eventTime
    ) {
    }

    // ============ 仓储内部行类型（不带明细，服务层再装配） ============

    /** 强排序列单行。 */
    public record SequenceRow(
            Long id,
            String zoneCode,
            Long tideVersion,
            BigDecimal tideLevel,
            String direction,
            Integer regimeVersion,
            Long expectedActiveSequenceId,
            String state,
            OffsetDateTime taskTime,
            LocalDateTime createdAt
    ) {
    }
}
