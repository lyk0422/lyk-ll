package com.example.starter.web.dto;

import java.util.List;

/**
 * 反复潜水余量积分联锁（虚构模型）的响应结果集合。
 */
public final class ResidualResult {

    private ResidualResult() {
    }

    /** 受控方案的单人积分视图。 */
    public record DiverPoints(
            String diverCode,
            boolean firstDive,
            String sourceDiveRequestId,
            Integer sourcePlanVersion,
            String sourceSurfacedAtUtc,
            Integer sourceResidualPoints,
            long surfaceIntervalMin,
            int currentPoints,
            int ndlMinutes,
            int allowedMinutes,
            boolean intervalGatePassed,
            boolean observationCompleted,
            boolean cumulative24hGatePassed) {
    }

    /** 受控方案结果：两人分别计算，全队允许时长取较短者。 */
    public record Plan(
            String requestId,
            int version,
            int depthM,
            int bottomMin,
            int ndlLimitMin,
            int teamAllowedMin,
            List<DiverPoints> divers,
            boolean replayed) {
    }

    /** 受控出水单人记账视图。 */
    public record SurfaceDiver(
            String diverCode,
            int preDivePoints,
            int bottomMin,
            int residualPoints,
            int accountVersion) {
    }

    /** 受控出水结果：两人积分账与本潜次记录已在同一事务原子更新。 */
    public record Surface(
            String requestId,
            int version,
            String surfacedAtUtc,
            String observationDueAtUtc,
            String status,
            List<SurfaceDiver> divers,
            boolean replayed) {
    }

    /** 历史记录更正结果。 */
    public record Correction(
            String requestId,
            String diveRequestId,
            long recordId,
            int planVersion,
            String diverCode,
            String phase,
            int recordedDurationMin,
            int correctedDurationMin,
            int deltaMin,
            int version,
            String correctedAtUtc,
            boolean replayed) {
    }
}
