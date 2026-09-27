package com.example.starter.web.dto;

import java.util.List;

/**
 * 受控反复潜水流程的响应体（虚构积分模型，不用于实际减压）。
 */
public final class ControlledResults {

    private ControlledResults() {
    }

    /** 受控作业申请受理结果。 */
    public record ControlledApplyResult(
            String requestId,
            String teamKey,
            String diverACode,
            String diverBCode,
            int plannedDepthM,
            int plannedBottomMin,
            String status,
            boolean pointsControlled,
            boolean replayed) {
    }

    /** 方案中单个潜水员的积分冻结与允许时长视图。 */
    public record DiverPointsView(
            String diverCode,
            String sourceDiveRequestId,
            int sourceVersion,
            String sourceSurfacedAtUtc,
            int pointsBefore,
            int allowedBottomMin,
            int predictedExitPoints) {
    }

    /** 受控方案结果：冻结每人来源版本/出水时刻/积分，允许时长取两人较短者。 */
    public record ControlledPlanResult(
            String requestId,
            int version,
            int depthM,
            int bottomMin,
            int ndlLimitMin,
            int teamAllowedBottomMin,
            String plannedDescentAtUtc,
            List<DiverPointsView> divers,
            int frozenOldVersions,
            boolean replayed) {
    }

    /** 实际下潜重查结果：来源未变才放行，并占潜水员级槽。 */
    public record ControlledStartResult(
            String requestId,
            int version,
            String status,
            String actualDescentAtUtc,
            List<StartedDiver> divers,
            boolean replayed) {

        public record StartedDiver(String diverCode, int pointsAtStart) {
        }
    }

    /** 出水原子记账结果：两人积分账与潜次记录同事务更新。 */
    public record ControlledSurfaceResult(
            String requestId,
            String status,
            String surfacedAtUtc,
            String observationDueAtUtc,
            int bottomMin,
            List<SettledDiver> divers,
            boolean replayed) {

        public record SettledDiver(String diverCode, int pointsBefore, int exitPoints) {
        }
    }

    /** 历史记录更正结果：仅追加独立差异，不回写已关闭潜次。 */
    public record CorrectionResult(
            String requestId,
            String diveRequestId,
            String diverCode,
            String phase,
            int recordedDurationMin,
            int correctedDurationMin,
            int deltaMin,
            boolean replayed) {
    }
}
