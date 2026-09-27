package com.example.starter.slope.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 边坡监测服务 API 请求与响应报文定义。所有时刻均为 Asia/Shanghai 本地时间（ISO-8601）。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 创建测区请求。速率阈值单位：毫米/小时、毫米/天。 */
    public record CreateZoneRequest(
            @NotBlank String requestId,
            @NotBlank String code,
            @NotBlank String name,
            @NotNull @Positive Double warnHourlyRate,
            @NotNull @Positive Double alarmDailyRate,
            @NotNull @Positive Double safetyHourlyRate,
            @NotNull @Positive Integer personnelTotal) {
    }

    public record ZoneResponse(long zoneId, String code, String status) {
    }

    /** 雷达读数上报请求。displacementMm 为累计位移（毫米）。 */
    public record IngestReadingRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime measuredAt,
            @NotNull Double displacementMm) {
    }

    /**
     * 读数受理结果。evaluation：NORMAL、YELLOW_CHAIN_CREATED、RED_CHAIN_CREATED、
     * CHAIN_UPGRADED_RED、RED_CHAIN_ACTIVE（红色链执行中的回落监测）、NONE（去重或迟到时不评估）。
     */
    public record ReadingResponse(
            Long readingId,
            boolean duplicated,
            boolean recordedLate,
            Double hourlyRate,
            Double dailyRate,
            String evaluation,
            Long chainId,
            String zoneStatus) {
    }

    /** 撤人确认请求；confirmedAt 恰满触发后30分钟判定为按时。 */
    public record EvacuationConfirmRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime confirmedAt,
            @NotNull @PositiveOrZero Integer arrivedCount) {
    }

    public record EvacuationConfirmResponse(
            long confirmationId,
            long chainId,
            boolean onTime,
            String zoneStatus) {
    }

    /** 复工评估申请；appliedAt 须不早于红色预警触发后24小时（恰满24小时允许）。 */
    public record ResumptionApplyRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime appliedAt) {
    }

    public record ResumptionApplyResponse(long assessmentId, String status) {
    }

    /** 复工双确认请求；type 取值 SITE_CHECK（现场核查）或 MONITORING_REVIEW（监测复核）。 */
    public record ResumptionConfirmRequest(
            @NotBlank String requestId,
            @NotBlank String type,
            @NotNull LocalDateTime confirmedAt) {
    }

    public record ResumptionConfirmResponse(
            long assessmentId,
            boolean siteCheckConfirmed,
            boolean monitoringReviewConfirmed,
            String assessmentStatus,
            String chainStatus,
            String zoneStatus) {
    }

    public record ArchiveRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime archivedAt) {
    }

    public record ArchiveResponse(long chainId, String status, LocalDateTime archivedAt) {
    }

    /** 速率曲线数据点，由位移读数复算得到。 */
    public record RatePoint(LocalDateTime measuredAt, double displacementMm,
                            Double hourlyRate, Double dailyRate) {
    }

    public record RateCurveResponse(long zoneId, List<RatePoint> points) {
    }

    public record LateEventItem(long id, Long chainId, LocalDateTime measuredAt,
                                double displacementMm, String reason) {
    }

    /** 处置链详情，含撤人确认与复工评估，供复算核对。 */
    public record ChainDetailResponse(
            long chainId,
            long zoneId,
            String level,
            String status,
            LocalDateTime triggeredAt,
            LocalDateTime evacuateDeadline,
            LocalDateTime evacuatedAt,
            LocalDateTime archivedAt,
            EvacuationInfo evacuation,
            AssessmentInfo assessment) {
    }

    public record EvacuationInfo(LocalDateTime confirmedAt, int arrivedCount, boolean onTime) {
    }

    public record AssessmentInfo(long assessmentId, LocalDateTime appliedAt,
                                 boolean siteCheckConfirmed, boolean monitoringReviewConfirmed,
                                 String status, LocalDateTime approvedAt) {
    }

    /**
     * 策略版本发布请求。expectedVersion 为调用方认为的当前最新版本（无策略时为0），
     * 成功发布后版本加1；effectiveFrom 不得早于 submittedAt。
     */
    public record PublishStrategyRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull @Positive Double yellowRate,
            @NotNull @Positive Double redRate,
            @NotNull @PositiveOrZero Double rainfall24hThreshold,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") Double reductionFactor,
            @NotNull LocalDateTime effectiveFrom,
            @NotNull LocalDateTime submittedAt) {
    }

    public record StrategyResponse(long strategyId, long zoneId, int version,
                                   double yellowRate, double redRate, double rainfall24hThreshold,
                                   double reductionFactor, LocalDateTime effectiveFrom,
                                   LocalDateTime submittedAt) {
    }

    /**
     * 合成雨量小时数据上报/更正请求。hourStart 须为UTC整点；rainfallMm 有限非负。
     * 同测区同小时同值重放返回既有记录；异值更正须携带 expectedVersion 等于当前版本。
     */
    public record ReportRainfallRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime hourStart,
            @NotNull @PositiveOrZero Double rainfallMm,
            @NotNull @PositiveOrZero Integer expectedVersion) {
    }

    public record RainfallResponse(long rainfallId, long zoneId, LocalDateTime hourStart,
                                   double rainfallMm, int version, boolean replayed,
                                   boolean corrected) {
    }

    /**
     * 有效阈值查询结果。strategyVersion 为 null 表示该时刻无生效策略，走测区静态安全线；
     * rainfallDataComplete=false 表示此前24个完整小时存在缺点（missingHours 列出），
     * 此时 rainfall24hSum 为 null（缺失不得当零），reductionApplied 为 false。
     */
    public record EffectiveThresholdResponse(long zoneId, LocalDateTime at,
                                             Integer strategyVersion,
                                             Double baseYellowRate, Double baseRedRate,
                                             boolean rainfallDataComplete,
                                             List<LocalDateTime> missingHours,
                                             Double rainfall24hSum,
                                             boolean reductionApplied,
                                             Double effectiveYellowRate, Double effectiveRedRate,
                                             Double effectiveSafetyRate,
                                             double legacySafetyHourlyRate) {
    }

    /** 复工安全评估冻结证据。 */
    public record ResumptionEvidenceItem(long evidenceId, long chainId, long assessmentId,
                                         LocalDateTime evaluatedAt,
                                         LocalDateTime windowStart, LocalDateTime windowEnd,
                                         LocalDateTime rainfallWindowStart, Double rainfall24hSum,
                                         boolean reductionApplied, Integer strategyVersion,
                                         double safetyThreshold, int readingCount, String detail) {
    }
}
