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
}
