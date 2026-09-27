package com.example.starter.slope.dto;

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
     * 合成雨量小时数据上报/更正请求。hourStart 须为整点；rainfallMm 有限非负。
     * expectedVersion=0 表示新增（同测区同小时同值重放返回已有记录，异值拒绝须显式更正）；
     * expectedVersion>0 表示更正，须等于当前版本，成功后版本加1。
     */
    public record RainfallHourRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime hourStart,
            @NotNull Double rainfallMm,
            @NotNull @PositiveOrZero Integer expectedVersion) {
    }

    public record RainfallHourResponse(long rainfallId, long zoneId, LocalDateTime hourStart,
                                       double rainfallMm, int version, boolean duplicated) {
    }

    /**
     * 阈值策略发布请求。须满足0&lt;黄&lt;红、0&lt;折减系数&lt;=1、雨量门槛有限非负、
     * 生效时刻不早于提交时刻。expectedVersion 须等于测区当前最新策略版本（无策略为0），
     * 成功发布后新版本=expectedVersion+1。
     */
    public record PublishStrategyRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull Double yellowHourlyRate,
            @NotNull Double redDailyRate,
            @NotNull Double rainfall24hThreshold,
            @NotNull Double reductionFactor,
            @NotNull LocalDateTime submittedAt,
            @NotNull LocalDateTime effectiveFrom) {
    }

    public record StrategyInfo(long strategyId, long zoneId, int version,
                               double yellowHourlyRate, double redDailyRate,
                               double rainfall24hThreshold, double reductionFactor,
                               LocalDateTime submittedAt, LocalDateTime effectiveFrom) {
    }

    public record PublishStrategyResponse(long strategyId, long zoneId, int version,
                                          LocalDateTime effectiveFrom) {
    }

    /**
     * 有效阈值查询结果。rainfallComplete=false 表示前24个完整小时雨量缺点，
     * 此时无法确定折减，有效阈值字段为NULL（缺点不当零）。
     */
    public record EffectiveThresholdResponse(long zoneId, LocalDateTime at, Integer strategyVersion,
                                             Double yellowHourlyRate, Double redDailyRate,
                                             Double safetyHourlyRate, Double rainfall24hMm,
                                             boolean rainfallComplete, boolean reductionApplied) {
    }

    /** 复工安全评价冻结证据条目。 */
    public record ResumptionEvidenceItem(long evidenceId, long assessmentId, long chainId,
                                         String phase, LocalDateTime evaluatedAt,
                                         LocalDateTime windowStart, int readingCount,
                                         Integer strategyVersion, Double rainfall24hMm,
                                         boolean reductionApplied, String result, String detail) {
    }
}
