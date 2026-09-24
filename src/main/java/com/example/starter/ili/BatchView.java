package com.example.starter.ili;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对齐批次视图：批次元信息、匹配对清单与未匹配读数清单。
 */
public record BatchView(
        long batchId,
        String requestId,
        String segmentId,
        String oldRunId,
        String newRunId,
        LocalDate oldRunDate,
        LocalDate newRunDate,
        BatchStatus status,
        LocalDateTime createdAt,
        LocalDateTime sealedAt,
        List<MatchView> matches,
        List<ReadingView> unmatchedReadings) {

    /**
     * 缺陷匹配对视图。
     */
    public record MatchView(
            long matchId,
            String oldDefectRef,
            String newDefectRef,
            BigDecimal oldMileageM,
            BigDecimal newMileageM,
            BigDecimal mileageDiffM,
            BigDecimal oldDepthPct,
            BigDecimal newDepthPct,
            BigDecimal growthRate,
            Grade grade,
            ReviewStatus reviewStatus,
            Grade finalGrade,
            String reviewedBy,
            LocalDateTime reviewedAt) {
    }

    /**
     * 未匹配读数视图（新发现或已消失）。
     */
    public record ReadingView(
            String runSide,
            String defectRef,
            BigDecimal mileageM,
            BigDecimal depthPct,
            MatchOutcome outcome) {
    }
}
