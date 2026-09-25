package com.example.starter.web.dto;

import java.util.List;

/**
 * 作业申请受理结果。
 */
public record ApplyResult(
        String requestId,
        String teamKey,
        String diverACode,
        String diverBCode,
        int plannedDepthM,
        int plannedBottomMin,
        String status,
        boolean replayed) {

    public record Segment(String phase, int fromDepthM, int toDepthM, int durationMin,
                          int rateLimitMMin) {
    }

    /** 减压方案结果；profile 为可复算的分段剖面。 */
    public record PlanResult(
            String requestId,
            int version,
            int depthM,
            int bottomMin,
            int ndlLimitMin,
            int rateLimitMMin,
            List<Segment> profile,
            int frozenOldVersions,
            boolean replayed) {
    }

    /** 水下记录结果；effective=FALSE 表示单人记录尚未成对生效。 */
    public record RecordResult(
            String requestId,
            int planVersion,
            String diverCode,
            String phase,
            String rateMMin,
            boolean speedAlert,
            boolean effective,
            String pairedWith,
            String status,
            int cumulativeBottomMin24h,
            boolean replayed) {
    }

    public record SurfaceResult(
            String requestId,
            String surfacedAtUtc,
            String observationDueAtUtc,
            String status,
            boolean replayed) {
    }

    public record VitalsResult(
            String requestId,
            String status,
            boolean symptomatic,
            boolean chamberProtocolStarted,
            boolean lateVitals,
            boolean replayed) {
    }

    public record CloseResult(
            String requestId,
            String status,
            String closedAtUtc,
            boolean terminal,
            boolean replayed) {
    }
}
