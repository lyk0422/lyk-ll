package com.example.starter.compliance.service;

import java.util.List;

/**
 * 清线门禁受控量产相关响应模型；时间字段均为 UTC epoch 毫秒。
 */
public final class LineControlResults {

    private LineControlResults() {
    }

    /** 配方版本过敏原登记视图：version 为乐观版本，从 1 开始，成功变更加 1。 */
    public record FormulaAllerView(
            Long registrationId,
            String productCode,
            Integer formulaVersion,
            List<String> allergens,
            Integer version,
            Long registeredAt,
            boolean replayed) {
    }

    /** 生产线视图：occupiedVersion 为当前递增占用版本，首笔受控量产前为 1。 */
    public record LineView(
            String lineCode,
            String lineName,
            String lastProductCode,
            Integer lastFormulaVersion,
            Integer occupiedVersion,
            Long updatedAt,
            boolean replayed) {
    }

    /** 清线凭证视图：expiresAt = confirmedAt + 2 小时，恰满 2 小时失效。 */
    public record CertificateView(
            Long certificateId,
            String certificateNo,
            String lineCode,
            List<String> allergens,
            String operatorCode,
            String reviewerCode,
            Long clearedAt,
            Integer occupiedVersion,
            String status,
            Long confirmedAt,
            Long expiresAt,
            Long consumedAt,
            boolean replayed) {
    }

    /** 受控量产结果：occupiedVersion 为该笔量产占用的线路版本。 */
    public record ControlledProductionView(
            Long productionRecordId,
            Long filingId,
            String lineCode,
            Integer occupiedVersion,
            String batchNo,
            String quantityKg,
            Long reportedAt,
            Long certificateId,
            boolean replayed) {
    }

    /** 线路量产历史（按提交先后升序）。 */
    public record LineProductionView(
            String lineCode,
            List<ControlledProductionView> productions) {
    }
}
