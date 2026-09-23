package com.example.starter.dr;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 需求响应 API 的请求与响应模型。时间统一使用 UTC（ISO-8601 带 Z 表示）。
 */
public final class ApiModels {

    private ApiModels() {
    }

    /** 创建事件请求：UTC 半开区间 [start,end) 与固定分钟粒度。 */
    public record CreateEventRequest(
            @NotBlank String eventId,
            @NotNull Instant start,
            @NotNull Instant end,
            @Positive int intervalMinutes) {
    }

    /** 登记电表请求：承诺削减功率（kW，非负）与基线版本标签。 */
    public record EnrollMeterRequest(
            @NotBlank String meterId,
            @NotNull @PositiveOrZero BigDecimal committedKw,
            @NotBlank String baselineVersion) {
    }

    /**
     * 曲线单点：编号、功率值（kW，非负）与该点前版本号。
     * expectedVersion 为空表示首次写入；更正时必须显式携带该电表该区间该类型的当前版本号。
     */
    public record CurvePointRequest(
            @NotNull @PositiveOrZero Integer intervalIndex,
            @NotNull @PositiveOrZero BigDecimal value,
            @Positive Integer expectedVersion) {
    }

    /** 曲线写入/更正请求，可一次提交多个区间点。 */
    public record CurveWriteRequest(
            @NotEmpty @Valid List<CurvePointRequest> points) {
    }

    /** 冻结请求中单块电表的证据版本（全区间一致的曲线版本）。 */
    public record FreezeMeterVersion(
            @NotBlank String meterId,
            @NotNull @Positive Integer baselineVersion,
            @NotNull @Positive Integer actualVersion) {
    }

    /** 冻结结算请求：requestId 幂等键、事件 expectedVersion 与各电表证据版本。 */
    public record FreezeRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion,
            @NotEmpty @Valid List<FreezeMeterVersion> meters) {
    }

    /** 登记电表视图。 */
    public record EnrollmentView(
            String meterId,
            BigDecimal committedKw,
            String baselineVersion) {
    }

    /** 事件视图。 */
    public record EventResponse(
            String eventId,
            Instant start,
            Instant end,
            int intervalMinutes,
            int intervalCount,
            int version,
            boolean frozen,
            List<EnrollmentView> enrollments) {
    }

    /** 曲线写入结果。 */
    public record CurveWriteResponse(
            String dataType,
            int dataVersion,
            int eventVersion,
            int acceptedPoints) {
    }

    /** 逐区间冻结证据。 */
    public record SettlementIntervalView(
            String meterId,
            int intervalIndex,
            Instant intervalStart,
            BigDecimal baselineValue,
            BigDecimal actualValue,
            int baselineVersion,
            int actualVersion,
            BigDecimal deliveredKw) {
    }

    /** 冻结结算证据。 */
    public record SettlementResponse(
            String settlementId,
            String requestId,
            String eventId,
            int eventVersion,
            BigDecimal totalDeliveredKw,
            Instant frozenAt,
            List<SettlementIntervalView> intervals) {
    }

    /** 冻结后更正形成的独立调整建议条目。 */
    public record AdjustmentView(
            String meterId,
            int intervalIndex,
            Instant intervalStart,
            BigDecimal frozenDeliveredKw,
            BigDecimal revisedBaselineKw,
            BigDecimal revisedActualKw,
            BigDecimal revisedDeliveredKw,
            BigDecimal deltaKw,
            Integer baselineVersion,
            Integer actualVersion,
            Instant updatedAt) {
    }

    /** 事件下全部调整建议。 */
    public record AdjustmentResponse(
            String settlementId,
            String eventId,
            BigDecimal totalDeltaKw,
            List<AdjustmentView> adjustments) {
    }

    /** 错误响应体。 */
    public record ErrorResponse(String error, String message) {
    }
}
