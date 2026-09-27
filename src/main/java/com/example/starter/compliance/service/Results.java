package com.example.starter.compliance.service;

import com.example.starter.compliance.engine.ReviewModels.ReviewReport;
import java.util.List;

/**
 * 服务层响应模型集合；所有金额类字段单位 g/kg，时间字段为 UTC epoch 毫秒，UTC 日界字段为 epochDay。
 */
public final class Results {

    private Results() {
    }

    /** 配方视图：state 为 SUBMITTED/REVIEWED/APPROVED，review 为最近一次审查报告（可空）。 */
    public record FormulaView(
            Long formulaId,
            String productCode,
            String productName,
            String categoryCode,
            Integer version,
            String state,
            Boolean reviewPassed,
            ReviewReport review,
            Long reviewedAt,
            Long approvedAt,
            boolean replayed) {
    }

    /** 备案视图：daysRemaining 仅复审观察期有值；截止日 = watchStartDay + 90，恰第 90 日受理。 */
    public record FilingView(
            Long filingId,
            Long formulaId,
            String productCode,
            Integer version,
            String status,
            Long watchStartDay,
            Long deadlineDay,
            Long daysRemaining,
            String suspendReason,
            Object snapshot,
            Long createdAt,
            Long finalizedAt,
            boolean replayed) {
    }

    public record ReviewView(Long formulaId, String state, ReviewReport report, boolean replayed) {
    }

    public record FilingReviewView(
            Long filingId,
            String status,
            boolean passed,
            ReviewReport report,
            Long reviewedAt,
            boolean replayed) {
    }

    public record ProductionView(
            Long filingId,
            String batchNo,
            String quantityKg,
            Long reportedAt,
            boolean replayed) {
    }

    public record StandardView(
            String additiveCode,
            String categoryCode,
            String maxAmount,
            Long updatedAt,
            List<Long> watchFilingIds,
            boolean replayed) {
    }

    public record SweepView(long todayEpochDay, List<Long> suspendedFilingIds) {
    }

    public record TrajectoryView(
            String productCode,
            List<FormulaView> formulas,
            List<FilingView> filings) {
    }

    // ---------------- 生产线过敏原清线凭证门禁 ----------------

    /** 配方版本过敏原登记视图：version 首次登记为 1，每次修改成功 +1。 */
    public record FormulaAllergenView(
            Long formulaId,
            Integer version,
            List<String> allergenCodes,
            Long updatedAt,
            boolean replayed) {
    }

    /** 生产线视图：occupancyVersion 为当前占用版本；lastFormulaId 为空表示首产前。 */
    public record ProductionLineView(
            String lineCode,
            String lineName,
            Long lastFormulaId,
            Integer occupancyVersion,
            Long createdAt,
            Long updatedAt) {
    }

    /**
     * 清线凭证视图：status VALID/CONSUMED/SUPERSEDED；
     * expiresAt = cleanedAt + 2 小时；valid 字段综合状态与时钟窗口（恰满 2 小时为 false）。
     */
    public record CleaningCertificateView(
            Long certificateId,
            String lineCode,
            Integer expectedVersion,
            Long occupiedFormulaId,
            String operatorId,
            String reviewerId,
            Long cleanedAt,
            Long expiresAt,
            List<String> allergenCodes,
            String status,
            boolean valid,
            Long consumedAt,
            Long createdAt,
            boolean replayed) {
    }

    /** 受控量产受理视图：certificateId 为空表示无需凭证的切换。 */
    public record ControlledProductionView(
            Long recordId,
            String lineCode,
            Integer occupancyVersion,
            Long formulaId,
            Long filingId,
            Long certificateId,
            String batchNo,
            String quantityKg,
            Long occurredAt,
            Long createdAt,
            boolean replayed) {
    }
}
