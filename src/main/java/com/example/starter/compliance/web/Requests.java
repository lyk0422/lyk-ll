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

    // ---------------- 生产线过敏原清线凭证门禁 ----------------

    /**
     * 配方版本过敏原登记/修改。
     *
     * @param expectedVersion 登记乐观锁版本：首次登记传 0（成功后版本为 1），修改传当前版本（成功 +1）
     * @param allergenCodes   过敏原编码集合（结构化数组，编码区分大小写，不靠分隔符拼接）
     */
    public record FormulaAllergenRequest(
            String requestId,
            Long formulaId,
            Integer expectedVersion,
            List<String> allergenCodes) {
    }

    /** 生产线登记：登记后占用版本从 1 开始，最近量产配方为空（首产无需清线凭证）。 */
    public record ProductionLineRequest(String requestId, String lineCode, String lineName) {
    }

    /**
     * 清线凭证双人确认：确认时由服务端固定线路当前占用版本与最近量产配方。
     *
     * @param cleanedAt     清线确认精确时刻（UTC epoch 毫秒）；有效期 [cleanedAt, cleanedAt + 2h)，恰满 2h 失效
     * @param allergenCodes 本次清线覆盖的过敏原编码集合（结构化数组）
     */
    public record CleaningCertificateRequest(
            String requestId,
            String lineCode,
            String operatorId,
            String reviewerId,
            Long cleanedAt,
            List<String> allergenCodes) {
    }

    /**
     * 受控量产受理（经过敏原清线门禁）。
     *
     * @param expectedVersion 客户端认知的线路当前占用版本；并发同版本仅一笔受理成功
     * @param certificateId   引用的清线凭证 ID；不需要凭证的切换必须为空
     * @param occurredAt      生产精确时刻（UTC epoch 毫秒），用于凭证 2 小时窗口判定
     */
    public record ControlledProductionRequest(
            String requestId,
            String lineCode,
            Integer expectedVersion,
            Long filingId,
            Long certificateId,
            String batchNo,
            BigDecimal quantityKg,
            Long occurredAt) {
    }
}
