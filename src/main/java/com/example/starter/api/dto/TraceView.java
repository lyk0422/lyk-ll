package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 复算追踪视图：力矩曲线、见证链、返工台账。
 */
public record TraceView(
        String cardNo,
        String cardStatus,
        List<TorquePoint> torqueCurve,
        List<WitnessPoint> witnessChain,
        List<ReworkPoint> reworkLedger) {

    /** 力矩曲线单点（追加链）。 */
    public record TorquePoint(
            String pointCode,
            int attemptNo,
            BigDecimal nominalTorque,
            BigDecimal measuredTorque,
            BigDecimal deviationPct,
            boolean inTolerance,
            String operator,
            String toolCode,
            Instant measuredAt) {
    }

    /** 见证链单点。 */
    public record WitnessPoint(
            int seq,
            String status,
            String witnessName,
            Instant eventTime,
            String note) {
    }

    /** 返工台账单点。 */
    public record ReworkPoint(
            String pointCode,
            int reworkNo,
            String requestId,
            String reason,
            Instant createdAt) {
    }
}
