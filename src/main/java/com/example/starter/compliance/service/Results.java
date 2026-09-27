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

    /** 生产线视图：occupancyVersion 为当前递增占用版本，lastFormulaId 为最近一次量产配方版本。 */
    public record LineView(
            String lineCode,
            Long lastFormulaId,
            Integer occupancyVersion,
            Long createdAt,
            Long updatedAt,
            boolean replayed) {
    }

    /** 配方版本过敏原登记视图：version 从 1 开始，每次成功变更 +1。 */
    public record AllergenView(
            Long formulaId,
            List<String> allergens,
            Integer version,
            Long updatedAt,
            boolean replayed) {
    }

    /**
     * 清线凭证视图：validUntil = confirmedAt + 2 小时（恰满即失效）；
     * effectiveStatus 为按当前时刻与线路占用版本计算的实效状态
     * （ISSUED 有效 / EXPIRED 超时 / INVALIDATED 被后续量产作废 / CONSUMED 已核销）。
     */
    public record VoucherView(
            Long voucherId,
            String lineCode,
            List<String> allergens,
            Long cleanedAt,
            Long confirmedAt,
            Long validUntil,
            Integer occupancyVersion,
            String status,
            String effectiveStatus,
            Long consumedAt,
            boolean replayed) {
    }

    /** 受控量产受理视图：occupancyVersion 为本次占用的线路版本（受理后线路递增到下一版本）。 */
    public record ControlledProductionView(
            Long recordId,
            Long filingId,
            String lineCode,
            String batchNo,
            String quantityKg,
            Integer occupancyVersion,
            Long voucherId,
            Long reportedAt,
            boolean replayed) {
    }
}
