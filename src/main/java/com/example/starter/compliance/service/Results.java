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

    /** 过敏原目录项（合成数据）。 */
    public record AllergenView(String allergenCode, String allergenName) {
    }

    /** 配方版本过敏原登记视图：version 为乐观变更版本（1 起，每次成功修改 +1）。 */
    public record FormulaAllergenView(
            Long formulaId,
            Integer version,
            List<String> allergens,
            Long updatedAt,
            boolean replayed) {
    }

    /** 生产线视图：lastFormulaId 为最近量产批准配方版本，occupiedVersion 为递增占用版本。 */
    public record ProductionLineView(
            String lineCode,
            String lineName,
            Long lastFormulaId,
            Integer occupiedVersion,
            Integer version,
            Long updatedAt) {
    }

    /**
     * 清线凭证视图：status 为 VALID/CONSUMED；validFrom=confirmedAt，validUntil=confirmedAt+2h，
     * 恰满 2 小时（validUntil）失效；occurredAt 为当前判定时刻（UTC 毫秒）。
     */
    public record ClearanceCertificateView(
            Long certificateId,
            String lineCode,
            String operatorId,
            String reviewerId,
            Long cleanedAt,
            Long confirmedAt,
            Long validUntil,
            Integer occupiedVersion,
            List<String> coveredAllergens,
            String status,
            Long consumedAt,
            Long controlledProductionId,
            Long now,
            boolean replayed) {
    }

    /** 受控量产记录视图。 */
    public record ControlledProductionView(
            Long controlledProductionId,
            String lineCode,
            Long filingId,
            Long formulaId,
            String batchNo,
            String quantityKg,
            Integer occupiedVersion,
            Long certificateId,
            Long occurredAt,
            boolean replayed) {
    }
}
