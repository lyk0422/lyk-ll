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

    /** 生产线登记：lineCode 全局唯一，占用版本从 1 开始。 */
    public record RegisterLineRequest(
            String requestId,
            String lineCode) {
    }

    /**
     * 配方版本过敏原集合登记/变更：expectedVersion 为乐观锁，
     * 首次登记传 null（成功后版本为 1），变更须等于当前版本，成功后 +1。
     */
    public record UpsertAllergensRequest(
            String requestId,
            Long formulaId,
            List<String> allergens,
            Integer expectedVersion) {
    }

    /**
     * 清线凭证确认：操作人与复核人须为两名不同人员；
     * cleanedAt 为清线时刻（UTC epoch 毫秒）；expectedOccupancyVersion 固定确认时线路占用版本。
     */
    public record ConfirmCleaningRequest(
            String requestId,
            String lineCode,
            List<String> allergens,
            Long cleanedAt,
            Integer expectedOccupancyVersion,
            String operator,
            String reviewer) {
    }

    /**
     * 受控量产声明：生产线 + 当前占用版本（expectedOccupancyVersion）；
     * 前批含新批未声明过敏原时 voucherId 必填且须覆盖缺口。
     */
    public record ControlledProductionRequest(
            String requestId,
            String lineCode,
            Long filingId,
            String batchNo,
            BigDecimal quantityKg,
            Integer expectedOccupancyVersion,
            Long voucherId) {
    }
}
