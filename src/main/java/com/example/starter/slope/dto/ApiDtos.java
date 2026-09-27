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
     * 雨量小时数据上报/更正请求。hourStart 须为UTC整点；rainfallMm 有限非负。
     * expectedVersion 为 NULL 表示首次上报；非 NULL 表示显式更正，须等于该小时当前版本。
     * reportedAt 为本次上报/更正的提交时刻（业务时间由请求报文控制，便于复算与可控时钟测试）。
     */
    public record RainfallReportRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime hourStart,
            @NotNull Double rainfallMm,
            Long expectedVersion,
            @NotNull LocalDateTime reportedAt) {
    }

    /** 雨量受理结果。replayed=true 表示同测区同小时同值重放，未产生新版本。 */
    public record RainfallHourResponse(
            long zoneId,
            LocalDateTime hourStart,
            double rainfallMm,
            long version,
            boolean replayed) {
    }

    /**
     * 策略发布请求。expectedVersion 须等于当前最新策略版本（无策略时为0），成功后新版本=expectedVersion+1。
     * 约束：0&lt;黄&lt;红、0&lt;折减系数&lt;=1、雨量门槛有限非负、生效时刻不早于提交时刻。
     */
    public record PolicyPublishRequest(
            @NotBlank String requestId,
            @NotNull Long expectedVersion,
            @NotNull Double yellowHourlyRate,
            @NotNull Double redHourlyRate,
            @NotNull Double rain24hThreshold,
            @NotNull Double reductionFactor,
            @NotNull LocalDateTime effectiveAt,
            @NotNull LocalDateTime submittedAt) {
    }

    public record PolicyPublishResponse(long zoneId, long version, LocalDateTime effectiveAt) {
    }

    /** 策略版本查询项。 */
    public record PolicyVersionInfo(long version, double yellowHourlyRate, double redHourlyRate,
                                    double rain24hThreshold, double reductionFactor,
                                    LocalDateTime effectiveAt, LocalDateTime submittedAt) {
    }

    /** 雨量小时当前值查询项（每小时取最大版本）。 */
    public record RainfallHourInfo(LocalDateTime hourStart, double rainfallMm, long version,
                                   LocalDateTime correctedAt) {
    }

    /** 雨量小时更正历史查询项（含全部版本）。 */
    public record RainfallCorrectionInfo(LocalDateTime hourStart, double rainfallMm, long version,
                                         LocalDateTime correctedAt) {
    }

    /**
     * 有效阈值查询结果。status：NO_POLICY无生效策略（回退测区静态阈值）、OK雨量齐备、
     * INSUFFICIENT_DATA此前24个完整小时雨量有缺（缺点不当零）。
     */
    public record EffectiveThresholdResponse(
            long zoneId,
            LocalDateTime at,
            String status,
            Long policyVersion,
            Double rain24hMm,
            Boolean reductionApplied,
            Double yellowHourlyRate,
            Double redHourlyRate,
            List<LocalDateTime> missingHours) {
    }

    /** 复工安全门禁冻结证据查询项。 */
    public record ResumptionEvidenceInfo(
            long id,
            long assessmentId,
            long chainId,
            String phase,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String result,
            String detail,
            LocalDateTime createdAt) {
    }
}
