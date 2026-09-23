package com.example.starter.dr.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 数据库行记录模型（内部使用）。
 */
public final class Rows {

    private Rows() {
    }

    /** dr_event 行。 */
    public record EventRow(
            String eventId,
            Instant start,
            Instant end,
            int intervalMinutes,
            int version,
            boolean frozen) {
    }

    /** dr_enrollment 行。 */
    public record EnrollmentRow(
            String eventId,
            String meterId,
            BigDecimal committedKw,
            String baselineVersion) {
    }

    /** 曲线当前值行（BASELINE / ACTUAL）。 */
    public record CurvePointRow(
            String eventId,
            String meterId,
            int intervalIndex,
            String dataType,
            BigDecimal value,
            int version) {
    }

    /** settlement 主表行。 */
    public record SettlementRow(
            String settlementId,
            String eventId,
            String requestId,
            int eventVersion,
            BigDecimal totalDeliveredKw,
            Instant frozenAt) {
    }

    /** settlement_interval 行。 */
    public record SettlementIntervalRow(
            String settlementId,
            String eventId,
            String meterId,
            int intervalIndex,
            Instant intervalStart,
            BigDecimal baselineValue,
            BigDecimal actualValue,
            int baselineVersion,
            int actualVersion,
            BigDecimal deliveredKw) {
    }

    /** freeze_request 幂等行。 */
    public record FreezeRequestRow(
            String requestId,
            String eventId,
            String fingerprint,
            String settlementId,
            Instant createdAt) {
    }

    /** adjustment_advice 行。 */
    public record AdjustmentRow(
            long id,
            String settlementId,
            String eventId,
            String meterId,
            int intervalIndex,
            BigDecimal frozenDeliveredKw,
            BigDecimal revisedBaselineKw,
            BigDecimal revisedActualKw,
            BigDecimal revisedDeliveredKw,
            BigDecimal deltaKw,
            Integer baselineVersion,
            Integer actualVersion,
            Instant createdAt,
            Instant updatedAt) {
    }
}
