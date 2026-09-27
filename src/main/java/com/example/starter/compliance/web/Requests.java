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

    // -------- 换配方清线（合成规则） --------

    /**
     * 配方版本过敏原登记/修改。
     *
     * @param expectedVersion 乐观变更版本：首次登记为 null（创建 version=1）；修改时必传当前版本，成功 +1
     * @param allergens       过敏原编码集合（完整集合语义：以本次入参整体替换）
     */
    public record FormulaAllergenRequest(
            String requestId,
            Long formulaId,
            Integer expectedVersion,
            List<String> allergens) {
    }

    /** 生产线登记：登记后 occupiedVersion=1、最近量产版本为空。 */
    public record ProductionLineRequest(
            String requestId,
            String lineCode,
            String lineName) {
    }

    /**
     * 清线凭证双人确认。
     *
     * @param cleanedAt 清线时刻（UTC epoch 毫秒，精确时刻入参）
     * @param confirmedAt 双人确认时刻（UTC epoch 毫秒）；凭证自该时刻起 2 小时内有效，恰满 2 小时失效
     * @param coveredAllergens 覆盖过敏原编码集合（必须非空）
     */
    public record ClearanceCertificateRequest(
            String requestId,
            String lineCode,
            String operatorId,
            String reviewerId,
            Long cleanedAt,
            Long confirmedAt,
            List<String> coveredAllergens) {
    }

    /**
     * 受控量产声明（新受控入口，必须通过过敏原清线门禁）。
     *
     * @param occupiedVersion 生产线当前占用版本（登记后为 1，每笔成功量产 +1）
     * @param occurredAt      申报的精确生产时刻（UTC epoch 毫秒）
     * @param certificateId   清线凭证主键；前批含新批未声明过敏原时必传，其他切换可为 null
     */
    public record ControlledProductionRequest(
            String requestId,
            String lineCode,
            Long filingId,
            String batchNo,
            BigDecimal quantityKg,
            Integer occupiedVersion,
            Long occurredAt,
            Long certificateId) {
    }
}
