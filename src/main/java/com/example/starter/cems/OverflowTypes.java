package com.example.starter.cems;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 雨季溢流证据标记与双口径统计（B 题）公共类型集合。
 * 溢流域所有时刻均为 <b>UTC 墙钟时间</b>（与旧 CEMS 域的 Asia/Shanghai 互不影响），
 * 小时固定在 UTC 整点、半开区间 [hour, hour+1)；雨量为合成单位 mm；浓度为 mg/L。
 */
public final class OverflowTypes {

    private OverflowTypes() {
    }

    /** 溢流许可版本状态。 */
    public enum PermitStatus {
        ACTIVE, SUPERSEDED
    }

    /**
     * 小时溢流标记。
     * NORMAL 正常排放；PENDING 观测溢流但缺雨量待核；
     * COMPLIANT 证据齐全的合规溢流（唯一可被正常排放均值排除的口径）；
     * DENIED 观测溢流但不满足许可（窗口/雨量/超长等）。
     */
    public enum OverflowStatus {
        NORMAL, PENDING, COMPLIANT, DENIED
    }

    // ---------------- 请求 ----------------

    /**
     * 新建排口首版合成溢流许可（version 固定为 1）。
     * 半开适用窗口 [windowStartUtc, windowEndUtc)。
     */
    public record CreatePermitRequest(
            String requestId,
            String outletCode,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            BigDecimal minHourlyRainfall,
            Integer maxConsecutiveHours
    ) {
    }

    /**
     * 修改溢流许可：必须携带 expectedVersion 乐观锁；成功后新版本号 = expectedVersion + 1。
     */
    public record UpdatePermitRequest(
            String requestId,
            String outletCode,
            Integer expectedVersion,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            BigDecimal minHourlyRainfall,
            Integer maxConsecutiveHours
    ) {
    }

    /**
     * 上报一个 UTC 整点小时数据：排放浓度必给；雨量可空（缺雨量只能待核）；
     * observedOverflow 表示该小时是否观测到溢流。
     */
    public record HourDataRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal dischargeValue,
            BigDecimal rainfallMm,
            boolean observedOverflow
    ) {
    }

    /**
     * 复核冻结之前更正某小时雨量：仅冻结前允许；成功后该小时雨量版本 +1 并重算其溢流标记。
     */
    public record CorrectRainfallRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal correctedRainfallMm
    ) {
    }

    /**
     * 复核冻结：以请求携带的精确 UTC 时刻冻结，固定当时许可版本与该小时雨量版本。
     */
    public record ReviewFreezeRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            LocalDateTime freezeAtUtc
    ) {
    }

    /**
     * 冻结后雨量独立调差：不改冻结版本、不回写小时记录、不改日结快照，仅独立留证。
     */
    public record RainfallAdjustmentRequest(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal newRainfallMm,
            String reason
    ) {
    }

    /**
     * 生成某 UTC 日的日结快照并返回双口径结果（竞争冻结一致；同日重复请求重放同一快照）。
     */
    public record DailySettlementRequest(
            String requestId,
            String outletCode,
            java.time.LocalDate dayUtc
    ) {
    }

    // ---------------- 响应 ----------------

    /** 溢流许可版本视图。 */
    public record PermitView(
            String outletCode,
            int version,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            BigDecimal minHourlyRainfall,
            int maxConsecutiveHours,
            String status,
            LocalDateTime createdAt
    ) {
    }

    /** 小时溢流记录视图。 */
    public record HourView(
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal dischargeValue,
            BigDecimal rainfallMm,
            int rainfallVersion,
            boolean observedOverflow,
            String overflowStatus,
            Integer evaluatedPermitVersion,
            Integer permitVersionFrozen,
            Integer rainfallVersionFrozen,
            LocalDateTime frozenAt,
            boolean deduplicated
    ) {
    }

    /** 雨量更正结果。 */
    public record RainfallCorrectionResult(
            String outletCode,
            LocalDateTime hourUtc,
            BigDecimal oldRainfallMm,
            BigDecimal newRainfallMm,
            int newRainfallVersion,
            String overflowStatusAfter
    ) {
    }

    /** 复核冻结结果。 */
    public record FreezeResult(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            String overflowStatus,
            int permitVersionFrozen,
            int rainfallVersionFrozen,
            LocalDateTime frozenAtUtc
    ) {
    }

    /** 冻结后独立雨量调差回执。 */
    public record AdjustmentResult(
            String requestId,
            String outletCode,
            LocalDateTime hourUtc,
            int frozenRainfallVersion,
            BigDecimal oldRainfallMm,
            BigDecimal newRainfallMm,
            String reason,
            LocalDateTime createdAt
    ) {
    }

    /**
     * 双口径均值结果。{@code present} 为实际到点小时清单（按小时升序）；
     * {@code missingHours} 为窗口内缺点小时清单；空集合无结果时 {@code noResult=true} 且均值为 null。
     */
    public record DualAverageResult(
            String outletCode,
            LocalDateTime windowStartUtc,
            LocalDateTime windowEndUtc,
            boolean noResult,
            BigDecimal normalAverage,
            BigDecimal allAverage,
            int normalDenominator,
            int allDenominator,
            int missingPointCount,
            int excludedCompliantCount,
            int pendingCount,
            List<HourView> present,
            List<LocalDateTime> missingHours
    ) {
    }

    /** 日结快照视图（双口径结果随快照冻结）。 */
    public record DailySettlementView(
            String outletCode,
            java.time.LocalDate dayUtc,
            BigDecimal normalAvg,
            BigDecimal allAvg,
            int normalDenominator,
            int allDenominator,
            int requestedHours,
            int presentCount,
            int excludedCount,
            int pendingCount,
            int missingPointCount,
            boolean replayed,
            LocalDateTime settledAt
    ) {
    }

    /** 专项异常视图。 */
    public record AnomalyView(
            String outletCode,
            String anomalyType,
            LocalDateTime segmentStartUtc,
            LocalDateTime segmentEndUtc,
            int segmentHours,
            int permitVersion,
            LocalDateTime createdAt
    ) {
    }
}
