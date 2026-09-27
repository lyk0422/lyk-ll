package com.example.starter.cems;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 雨季溢流证据与双口径统计的公共类型集合（cems-02 新增，合成数据，不用于生产）。
 * 时刻约定：UTC 精确时刻以 {@link Instant} 表示；小时数据固定 UTC 整点；
 * createdAt/frozenAt 等系统时间为 Asia/Shanghai 本地时间。
 * 单位：雨量 mm，排放浓度 mg/L。
 */
public final class OverflowTypes {

    private OverflowTypes() {
    }

    /** 溢流小时标记状态：PENDING 待核（缺雨量）/ COMPLIANT 合规溢流 / NORMAL 非合规溢流或普通小时。 */
    public enum MarkStatus {
        PENDING, COMPLIANT, NORMAL
    }

    // ---------------- 请求 ----------------

    /**
     * 新增溢流许可版本：expectedVersion 为当前最新版本号（无许可时传 0），
     * 版本从 1 开始、成功变更加 1；窗口为 UTC 半开区间 [windowStartUtc, windowEndUtc)。
     */
    public record PermitUpsertRequest(
            String requestId,
            String outletCode,
            Integer expectedVersion,
            Instant windowStartUtc,
            Instant windowEndUtc,
            BigDecimal minRainfallMm,
            Integer maxConsecutiveHours
    ) {
    }

    /** 单条小时数据：UTC 整点；rainfallMm 为 null 表示缺雨量（只能待核）；avgValue 为该小时排放浓度（mg/L）。 */
    public record OverflowHourItem(
            Instant hourUtc,
            BigDecimal rainfallMm,
            Boolean overflowFlag,
            BigDecimal avgValue
    ) {
    }

    /** 小时数据批量上报：整批同一事务；幂等指纹覆盖集合内全部精确时刻与数值。 */
    public record HourReportRequest(
            String requestId,
            String outletCode,
            List<OverflowHourItem> hours
    ) {
    }

    /** 雨量更正：expectedVersion 为当前雨量版本号；复核冻结前重算标记，冻结后只登记独立调差。 */
    public record RainfallCorrectionRequest(
            String requestId,
            String outletCode,
            Instant hourUtc,
            BigDecimal rainfallMm,
            Integer expectedVersion
    ) {
    }

    /** 日结核查冻结：冻结所用许可版本、雨量版本与双口径结果。 */
    public record ReviewFreezeRequest(
            String requestId,
            String outletCode,
            LocalDate dayUtc
    ) {
    }

    // ---------------- 响应 ----------------

    /** 溢流许可版本视图。 */
    public record PermitView(
            String outletCode,
            int version,
            Instant windowStartUtc,
            Instant windowEndUtc,
            BigDecimal minRainfallMm,
            int maxConsecutiveHours,
            String status,
            LocalDateTime createdAt
    ) {
    }

    /** 单小时标记视图：permitVersion 仅合规溢流携带，其余为 null。 */
    public record HourMarkView(
            Instant hourUtc,
            String markStatus,
            Integer permitVersion,
            BigDecimal rainfallMm,
            boolean overflowFlag,
            BigDecimal avgValue,
            boolean deduplicated
    ) {
    }

    /** 小时数据批量上报结果。 */
    public record HourReportResult(
            String requestId,
            String outletCode,
            int reported,
            int deduplicated,
            List<HourMarkView> hours
    ) {
    }

    /**
     * 雨量更正结果：frozen=true 表示当日已冻结，本次仅登记独立调差、不改日结快照；
     * markStatus 为重算后（或冻结时保持）的标记状态。
     */
    public record RainfallCorrectionResult(
            String requestId,
            String outletCode,
            Instant hourUtc,
            int rainfallVersion,
            boolean frozen,
            String markStatus,
            boolean adjustmentRecorded
    ) {
    }

    /** 日结复核快照：双口径均值空集合为 null（无结果而非零），分母与缺点数明示。 */
    public record ReviewView(
            String outletCode,
            LocalDate dayUtc,
            Integer permitVersion,
            int rainfallVersion,
            BigDecimal normalAvg,
            int normalCount,
            BigDecimal allAvg,
            int allCount,
            int missingCount,
            String status,
            LocalDateTime frozenAt
    ) {
    }

    /**
     * 双口径均值查询结果：normal* 仅排除证据齐全的合规溢流小时，all* 不排除；
     * 空集合均值为 null（无结果而非零）；missingHours 明示缺点整点。
     */
    public record AveragesView(
            String outletCode,
            Instant fromUtc,
            Instant toUtc,
            long totalSlots,
            int reportedCount,
            int missingCount,
            int pendingCount,
            BigDecimal normalAvg,
            int normalCount,
            BigDecimal allAvg,
            int allCount,
            List<Instant> missingHours
    ) {
    }

    /** 专项异常视图：连续合规溢流段严格超过许可最长连续小时数。 */
    public record AnomalyView(
            String outletCode,
            Instant segmentStartUtc,
            Instant segmentEndUtc,
            int hourCount,
            int maxAllowed,
            int permitVersion,
            String reason,
            String status
    ) {
    }
}
