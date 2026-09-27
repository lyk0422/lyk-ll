package com.example.starter.compliance.web;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口请求 DTO 集合；amount 单位 g/kg，ratio 为质量比（0~1）。
 */
public final class Requests {

    private Requests() {
    }

    public record DirectAdditiveDto(String additiveCode, BigDecimal amount) {
    }

    public record IngredientDto(String ingredientCode, BigDecimal ratio) {
    }

    public record SubmitFormulaRequest(
            String requestId,
            String productCode,
            String productName,
            String categoryCode,
            Integer version,
            List<DirectAdditiveDto> additives,
            List<IngredientDto> ingredients) {
    }

    public record FormulaRefRequest(String requestId, Long formulaId) {
    }

    public record FilingRefRequest(String requestId, Long filingId) {
    }

    public record FileFilingRequest(String requestId, Long formulaId) {
    }

    public record StandardUpdateRequest(
            String requestId,
            String additiveCode,
            String categoryCode,
            BigDecimal maxAmount) {
    }

    public record ProductionReportRequest(
            String requestId,
            Long filingId,
            String batchNo,
            BigDecimal quantityKg) {
    }

    /**
     * 配方版本过敏原登记（新增/修改）。
     * expectedVersion：新增传 0（成功后版本为 1），修改传当前版本；成功后版本加 1。
     * registeredAt 为业务精确时刻（UTC epoch 毫秒）。
     */
    public record FormulaAllergenRequest(
            String requestId,
            String productCode,
            Integer formulaVersion,
            List<String> allergens,
            Integer expectedVersion,
            Long registeredAt) {
    }

    /**
     * 生产线登记；lastProductCode/lastFormulaVersion 同时提供或同时留空，
     * 用于合成登记最近一次量产版本；占用版本初始固定为 1。
     */
    public record RegisterLineRequest(
            String requestId,
            String lineCode,
            String lineName,
            String lastProductCode,
            Integer lastFormulaVersion,
            Long registeredAt) {
    }

    /**
     * 清线凭证双人确认：操作人与复核人必须为不同人员；
     * clearedAt 清线时刻、confirmedAt 双人确认时刻（UTC epoch 毫秒），
     * 凭证固定当前线路占用版本，确认后 2 小时内有效（恰满 2 小时失效）。
     */
    public record ConfirmCertificateRequest(
            String requestId,
            String certificateNo,
            String lineCode,
            List<String> allergens,
            String operatorCode,
            String reviewerCode,
            Long clearedAt,
            Long confirmedAt) {
    }

    /**
     * 受控量产：声明线路与当前占用版本；当前批含有上一批遗留过敏原时必须携带有效凭证。
     * reportedAt 为业务精确时刻（UTC epoch 毫秒）。
     */
    public record ControlledProductionRequest(
            String requestId,
            Long filingId,
            String lineCode,
            Integer occupiedVersion,
            String batchNo,
            BigDecimal quantityKg,
            Long certificateId,
            Long reportedAt) {
    }
}
