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

    // ------------------------------------------------------------------
    // 雨量联动阈值版本与复工安全门禁
    // ------------------------------------------------------------------

    /**
     * 阈值策略发布请求。expectedVersion 为测区当前最新策略版本号（首版传0），
     * 发布成功后版本加1；速率阈值要求 0&lt;黄&lt;红，reductionFactor 取值 (0,1]。
     * effectiveAt 不得早于 submittedAt（恰等允许）；所有时刻为 Asia/Shanghai 本地时间。
     */
    public record PolicyPublishRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull @Positive Double yellowHourlyRate,
            @NotNull @Positive Double redDailyRate,
            @NotNull @PositiveOrZero Double rainGateMm,
            @NotNull Double reductionFactor,
            @NotNull LocalDateTime submittedAt,
            @NotNull LocalDateTime effectiveAt) {
    }

    public record PolicyVersionResponse(
            long policyId,
            int version,
            double yellowHourlyRate,
            double redDailyRate,
            double rainGateMm,
            double reductionFactor,
            LocalDateTime submittedAt,
            LocalDateTime effectiveAt,
            boolean tightenedFromPrevious) {
    }

    /**
     * 合成小时雨量写入/更正请求。hourUtc 为 UTC 整点墙钟时间（分秒必须为0），
     * rainMm 须有限且非负。首写 expectedVersion 传0；同小时同值重放不升版，
     * 异值更正须携带当前版本号，成功后加1。
     */
    public record RainfallHourRequest(
            @NotBlank String requestId,
            @NotNull LocalDateTime hourUtc,
            @NotNull Double rainMm,
            @NotNull Integer expectedVersion) {
    }

    /**
     * 小时雨量受理结果。outcome：CREATED首写、REPLAYED同值自然重放、CORRECTED异值更正。
     */
    public record RainfallHourResponse(
            long rainfallId,
            LocalDateTime hourUtc,
            double rainMm,
            int version,
            String outcome) {
    }

    public record RainfallHourItem(long id, LocalDateTime hourUtc, double rainMm, int version) {
    }

    /**
     * 指定时刻实际生效的阈值查询结果。
     * dataSufficient=false 时近24个完整UTC小时存在缺点，effective* 为 null，
     * 缺点不得按零处理；missingHoursUtc 为缺失的 UTC 整点列表。
     */
    public record EffectiveThresholdResponse(
            long zoneId,
            LocalDateTime at,
            Integer policyVersion,
            LocalDateTime policyEffectiveAt,
            Double baseYellowHourlyRate,
            Double baseRedDailyRate,
            Double baseSafetyHourlyRate,
            Double rainGateMm,
            Double reductionFactor,
            boolean reductionApplied,
            Double rainSum24h,
            Double effectiveYellowHourlyRate,
            Double effectiveRedDailyRate,
            Double effectiveSafetyHourlyRate,
            boolean dataSufficient,
            List<LocalDateTime> missingHoursUtc,
            List<RainfallHourItem> rainHours) {
    }

    /** 复工证据快照内的逐时刻评价项。 */
    public record EvidenceMoment(
            LocalDateTime measuredAt,
            Double hourlyRate,
            Integer policyVersion,
            boolean reductionApplied,
            Double rainSum24h,
            double effectiveSafetyHourlyRate,
            boolean safe) {
    }

    /** 复工证据快照内的双确认项。 */
    public record EvidenceConfirmation(String type, LocalDateTime confirmedAt) {
    }

    /** 批准时冻结的一致证据快照（结构化 JSON 落库）。 */
    public record EvidenceSnapshot(
            long zoneId,
            String zoneCode,
            long chainId,
            LocalDateTime triggeredAt,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            LocalDateTime lastTightenedAt,
            List<PolicyVersionResponse> policyVersions,
            List<EvidenceMoment> moments,
            List<EvidenceConfirmation> confirmations) {
    }

    public record ResumptionEvidenceResponse(
            long evidenceId,
            long assessmentId,
            long chainId,
            long zoneId,
            LocalDateTime frozenAt,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            LocalDateTime lastTightenedAt,
            EvidenceSnapshot snapshot) {
    }
}
