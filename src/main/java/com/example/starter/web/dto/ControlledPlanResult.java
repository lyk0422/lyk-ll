package com.example.starter.web.dto;

import java.util.List;

/**
 * 反复潜水积分受控方案结果：含两人冻结积分、门禁结论与两人较短允许分钟数。
 */
public record ControlledPlanResult(
        String requestId,
        String diveRequestId,
        int planVersion,
        String atUtc,
        int depthM,
        int requestedBottomMin,
        int teamAllowedMin,
        boolean bottomWithinAllowed,
        List<DiverScoreView> divers,
        int frozenOldVersions,
        boolean replayed) {

    /** 方案中每名潜水员的冻结积分与门禁视图。 */
    public record DiverScoreView(
            String diverCode,
            int expectedVersion,
            String lastSurfacedAtUtc,
            int residualScore,
            long surfaceMinutes,
            int currentScore,
            int ndlMinutes,
            int allowedMin,
            boolean intervalPassed,
            boolean observationComplete) {
    }
}
