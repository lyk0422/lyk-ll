package com.example.starter.cems;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 污水排放在线监测与限排联动服务的公共类型集合（枚举、请求/响应记录）。
 * 时间均为 Asia/Shanghai 本地时间；浓度单位 mg/L。
 */
public final class CemsTypes {

    private CemsTypes() {
    }

    /** 排口流转状态：许可登记→在线监测→限排执行→复测解除→归档。 */
    public enum OutletStatus {
        REGISTERED, MONITORING, RESTRICTED, ARCHIVED
    }

    /** 限排令状态。 */
    public enum OrderStatus {
        PENDING_CONFIRM, ACTIVE, ESCALATED, CLOSED
    }

    /** 超标标记类型。 */
    public enum MarkType {
        DAILY_AVG, HOURLY_AVG
    }

    /** 监测时段类型。 */
    public enum PeriodType {
        HOUR, DAY
    }

    /** 超标标记状态。 */
    public enum MarkStatus {
        ACTIVE, RESOLVED
    }

    /** 数据缺失督办状态。 */
    public enum GapStatus {
        SUPERVISED, RESOLVED
    }

    // ---------------- 请求 ----------------

    /** 许可登记：登记排口及许可浓度限值（含本值达标）。 */
    public record RegisterOutletRequest(
            String requestId,
            String outletCode,
            String outletName,
            BigDecimal dailyLimit,
            BigDecimal hourlyLimit,
            String unit
    ) {
    }

    /** 日均值评价：超过日许可限值生成超标标记，恰等限值判达标；requestId 幂等。 */
    public record DailyEvalRequest(
            String requestId,
            String outletCode,
            LocalDate day,
            BigDecimal avgValue
    ) {
    }

    /** 小时均值上报：按排口＋整点时段去重；连续 3 次小时超标自动下达限排令。 */
    public record HourlyReadingRequest(
            String outletCode,
            LocalDateTime hour,
            BigDecimal avgValue
    ) {
    }

    /** 连续缺失检查：缺失区间严格大于 2 小时才督办，恰 2 小时不标记。 */
    public record GapCheckRequest(
            String outletCode,
            LocalDateTime missingFrom,
            LocalDateTime missingTo
    ) {
    }

    /** 限排令确认执行。 */
    public record ConfirmOrderRequest(
            String requestId,
            String orderNo
    ) {
    }

    /** 超时升级巡检：恰满 2 小时未确认的限排令升级并联动限产。 */
    public record EscalateSweepRequest(
            String outletCode
    ) {
    }

    /** 复测合格解除：复测小时均值合格方可解除，与限排令关闭同一事务。 */
    public record ReleaseRestrictionRequest(
            String requestId,
            String orderNo,
            LocalDateTime retestHour,
            BigDecimal retestValue
    ) {
    }

    /** 排口归档：终态；重复归档幂等成功。 */
    public record ArchiveOutletRequest(
            String requestId,
            String outletCode
    ) {
    }

    /** 复算请求：重算标记链、缺失督办与限排记录。 */
    public record RecomputeRequest(
            String outletCode
    ) {
    }

    // ---------------- 响应 ----------------

    /** 排口视图。 */
    public record OutletView(
            String outletCode,
            String outletName,
            String status,
            BigDecimal dailyLimit,
            BigDecimal hourlyLimit,
            String limitUnit,
            LocalDateTime createdAt,
            LocalDateTime archivedAt
    ) {
    }

    /** 日均值评价结果。 */
    public record DailyEvalResult(
            String requestId,
            String outletCode,
            LocalDate day,
            BigDecimal avgValue,
            BigDecimal dailyLimit,
            boolean exceeded,
            boolean late,
            Long markId
    ) {
    }

    /** 小时读数处理结果。 */
    public record HourlyReadingResult(
            String outletCode,
            LocalDateTime hour,
            BigDecimal avgValue,
            BigDecimal hourlyLimit,
            boolean deduplicated,
            boolean exceeded,
            boolean late,
            int chainIndex,
            boolean restrictionTriggered,
            String orderNo,
            String orderStatus
    ) {
    }

    /** 缺失检查结果。 */
    public record GapResult(
            String outletCode,
            LocalDateTime missingFrom,
            LocalDateTime missingTo,
            long gapMinutes,
            boolean marked,
            LocalDateTime supervisedAt
    ) {
    }

    /** 限排令视图。 */
    public record OrderView(
            String orderNo,
            String outletCode,
            String status,
            BigDecimal frozenTotalQty,
            LocalDateTime issuedAt,
            LocalDateTime confirmDeadline,
            LocalDateTime confirmedAt,
            LocalDateTime escalatedAt,
            String escalateReason,
            BigDecimal retestValue,
            LocalDateTime closedAt
    ) {
    }

    /** 解除限排结果。 */
    public record ReleaseResult(
            String requestId,
            String orderNo,
            String outletCode,
            String status,
            LocalDateTime closedAt
    ) {
    }

    /** 归档结果。 */
    public record ArchiveResult(
            String requestId,
            String outletCode,
            boolean archived,
            LocalDateTime archivedAt
    ) {
    }

    /** 复算结果：标记链、缺失督办、限排记录均按读数重算。 */
    public record RecomputeResult(
            String outletCode,
            int hourlyMarkCount,
            int dailyMarkCount,
            int activeOrderCount,
            int gapCount,
            List<String> details
    ) {
    }

    /** 统一操作回执。 */
    public record Receipt(String requestId, String action, boolean replayed) {
    }

    // ---------------- 雨季溢流证据标记与双口径统计（业务时刻均为 UTC） ----------------

    /** 溢流小时评估状态。 */
    public enum OverflowEvalStatus {
        /** 非溢流小时。 */
        NORMAL,
        /** 合规溢流：许可有效、雨量≥门槛（恰等合法）、该小时完整落窗，证据齐全。 */
        COMPLIANT,
        /** 待核：缺雨量，不能当零也不能直接豁免。 */
        PENDING_REVIEW,
        /** 不合规溢流：有雨量但无有效许可、未完整落窗或雨量低于门槛。 */
        NON_COMPLIANT
    }

    /** 双口径统计口径。 */
    public enum StatsCaliber {
        /** 正常排放均值：仅排除证据齐全的合规溢流小时。 */
        NORMAL,
        /** 全部排放均值：不排除任何小时。 */
        ALL
    }

    /**
     * 溢流许可版本新增/修改（统一入口）：expectedVersion 为当前版本乐观校验，
     * 无许可时传 0 表示新增（成功写入版本 1），每次成功变更加 1。
     * validFrom/validTo 为 UTC 半开适用窗口 [validFrom, validTo)。
     */
    public record OverflowPermitRequest(
            String requestId,
            String outletCode,
            Integer expectedVersion,
            LocalDateTime validFrom,
            LocalDateTime validTo,
            BigDecimal minRainfall,
            Integer maxConsecutiveHours
    ) {
    }

    /** 溢流许可版本视图；窗口为 UTC 半开。 */
    public record OverflowPermitView(
            String outletCode,
            int version,
            LocalDateTime validFrom,
            LocalDateTime validTo,
            BigDecimal minRainfall,
            int maxConsecutiveHours,
            LocalDateTime updatedAt
    ) {
    }

    /** 小时溢流数据条目：hour 为 UTC 整点；rainfall 为 null 表示缺雨量（只能待核）。 */
    public record OverflowHourEntry(
            LocalDateTime hour,
            BigDecimal rainfall,
            Boolean overflowFlag
    ) {
    }

    /** 小时溢流数据上报/更正（集合）；复核冻结前更正雨量会雨量版本加 1 并重算。 */
    public record OverflowHoursRequest(
            String requestId,
            String outletCode,
            List<OverflowHourEntry> entries
    ) {
    }

    /** 小时溢流数据视图。 */
    public record OverflowHourView(
            LocalDateTime hour,
            BigDecimal rainfall,
            int rainfallVersion,
            boolean overflowFlag,
            String evalStatus,
            Integer permitVersion,
            boolean reviewed
    ) {
    }

    /** 小时溢流数据上报结果。 */
    public record OverflowHoursResult(
            String outletCode,
            List<OverflowHourView> hours
    ) {
    }

    /** 标记复核请求：冻结该 UTC 日所用许可及雨量版本，并冻结日结快照。 */
    public record OverflowReviewRequest(
            String requestId,
            String outletCode,
            LocalDate day
    ) {
    }

    /** 日结请求：冻结该 UTC 日的双口径快照；与标记复核竞争冻结，先冻者为准。 */
    public record OverflowSettlementRequest(
            String requestId,
            String outletCode,
            LocalDate day
    ) {
    }

    /** 日结快照视图：双口径结果、分母与缺点明示；空集合均值为 null（无结果而非零）。 */
    public record OverflowSnapshotView(
            String outletCode,
            LocalDate day,
            Integer permitVersion,
            String rainfallVersions,
            BigDecimal normalAvg,
            int normalCount,
            BigDecimal allAvg,
            int allCount,
            List<LocalDateTime> missingHours,
            String frozenBy,
            LocalDateTime frozenAt
    ) {
    }

    /** 专项异常视图：连续合规溢流段超过许可最大连续小时数。 */
    public record OverflowAnomalyView(
            LocalDateTime segmentStart,
            LocalDateTime segmentEnd,
            int segmentHours,
            int maxAllowed
    ) {
    }

    /** 标记复核结果：冻结小时数、专项异常与一致的日结快照。 */
    public record OverflowReviewResult(
            String outletCode,
            LocalDate day,
            int reviewedHours,
            List<OverflowAnomalyView> anomalies,
            OverflowSnapshotView snapshot
    ) {
    }

    /** 独立调差请求：仅允许在日结快照冻结后调用，不改快照。 */
    public record OverflowAdjustmentRequest(
            String requestId,
            String outletCode,
            LocalDateTime hour,
            BigDecimal adjustValue,
            String reason
    ) {
    }

    /** 独立调差视图。 */
    public record OverflowAdjustmentView(
            long id,
            String outletCode,
            LocalDateTime hour,
            BigDecimal adjustValue,
            String reason,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 双口径统计视图：average 为 null 表示空集合无结果（而非零）；
     * denominator 为分母（计入小时数）；missingHours 为缺点（无浓度读数的 UTC 整点）；
     * excludedCompliantOverflowHours 为正常口径排除的证据齐全合规溢流小时（全口径恒为空表）。
     */
    public record DischargeStatsView(
            String outletCode,
            LocalDateTime from,
            LocalDateTime to,
            String caliber,
            BigDecimal average,
            int denominator,
            List<LocalDateTime> missingHours,
            List<LocalDateTime> excludedCompliantOverflowHours
    ) {
    }
}
