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

    // ================= 雨季溢流（UTC 口径，合成许可） =================

    /**
     * 新建溢流许可版本。UTC 半开窗口 [validFromUtc, validToUtc)，两端必须为 UTC 整点。
     * 首个版本号固定为 1；与既有版本窗口重叠拒绝（422）。
     */
    public record OverflowPermitCreateRequest(
            String requestId,
            String outletCode,
            LocalDateTime validFromUtc,
            LocalDateTime validToUtc,
            BigDecimal minHourlyRainfallMm,
            Integer maxConsecutiveOverflowHours
    ) {
    }

    /**
     * 修改溢流许可：必须携带 expectedVersion（当前最新版本），成功后版本加 1。
     */
    public record OverflowPermitUpdateRequest(
            String requestId,
            String outletCode,
            Integer expectedVersion,
            LocalDateTime validFromUtc,
            LocalDateTime validToUtc,
            BigDecimal minHourlyRainfallMm,
            Integer maxConsecutiveOverflowHours
    ) {
    }

    /** 溢流许可版本视图。 */
    public record OverflowPermitView(
            String outletCode,
            int version,
            LocalDateTime validFromUtc,
            LocalDateTime validToUtc,
            BigDecimal minHourlyRainfallMm,
            int maxConsecutiveOverflowHours,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 上报/更正某 UTC 整点小时雨量。version 从 1 开始：首次上报 expectedVersion 留空且落 version 1；
     * 更正须携带 expectedVersion=当前最新版本，成功后新增一行 version+1（历史版本保留）。
     * 雨量 0.0 是合法实测值；缺雨量是“无任何版本行”，不得用 0 代替。requestId 幂等。
     */
    public record RainfallReportRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal rainfallMm,
            Integer expectedVersion
    ) {
    }

    /** 雨量上报结果（回显最新版本）。 */
    public record RainfallReportResult(
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal rainfallMm,
            int version
    ) {
    }

    /**
     * 上报 UTC 整点小时排放数据并申报溢流。overflowFlag=true 时进入溢流证据判定：
     * 许可有效、该小时雨量（最新版本）≥门槛、小时完整落窗才证据齐全；缺雨量只能待核。
     * 浓度与溢流标记独立留证：溢流不抹掉原浓度超标，也不取消限排令。requestId 幂等。
     */
    public record OverflowHourlyRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal avgValue,
            boolean overflowFlag
    ) {
    }

    /** 小时溢流证据视图。 */
    public record OverflowHourView(
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal avgValue,
            boolean overflowFlag,
            String overflowStatus,
            Integer permitVersion,
            Integer rainfallVersion,
            BigDecimal rainfallMm
    ) {
    }

    /**
     * 标记复核：冻结指定溢流小时所用许可版本与雨量版本，并在同一事务产出该 UTC 日双口径日结。
     * requestId 幂等：同键同参重放首次冻结结果；小时须证据齐全（READY）方可冻结。
     */
    public record OverflowReviewRequest(
            String requestId,
            String outletCode,
            LocalDate dayUtc
    ) {
    }

    /**
     * 冻结后独立雨量调差：不改日结快照、不改冻结版本列，仅追加调差证据。
     */
    public record RainfallAdjustRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal correctedRainfallMm,
            String reason
    ) {
    }

    /** 独立调差结果。 */
    public record RainfallAdjustResult(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            Integer frozenRainfallVersion,
            BigDecimal frozenRainfallMm,
            int newRainfallVersion,
            BigDecimal newRainfallMm
    ) {
    }

    /** 单口径日结：分母、排除小时、缺点均明示。 */
    public record OverflowCaliberSummary(
            String caliber,
            BigDecimal averageValue,
            int denominator,
            int excludedHours,
            int missingPoints,
            boolean noData
    ) {
    }

    /** 双口径日结视图：NORMAL 仅排除证据齐全的合规溢流小时；ALL 不排除；空集合返回无结果而非零。 */
    public record OverflowDailySummaryView(
            String outletCode,
            LocalDate dayUtc,
            boolean replayed,
            OverflowCaliberSummary normal,
            OverflowCaliberSummary all
    ) {
    }

    /** 专项异常视图。 */
    public record OverflowAnomalyView(
            String outletCode,
            String anomalyType,
            LocalDateTime segmentFromUtc,
            LocalDateTime segmentToUtc,
            int consecutiveHours,
            int limitHours,
            Integer permitVersion,
            String detail
    ) {
    }

    /** 标记复核/日结冻结结果：双口径与专项异常在同一冻结事务内一致产出。 */
    public record OverflowReviewResult(
            String requestId,
            String outletCode,
            LocalDate dayUtc,
            boolean replayed,
            int frozenCompliantHours,
            OverflowDailySummaryView summary,
            List<OverflowAnomalyView> anomalies
    ) {
    }
}
