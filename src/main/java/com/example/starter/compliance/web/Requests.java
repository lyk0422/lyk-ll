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
}
