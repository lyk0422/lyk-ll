package com.example.starter.compliance.repo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 返工料登记行：冻结来源配方版本与过敏原集合；availableKg 为登记原量（公斤，最多三位小数），
 * consumedKg 为累计消费；version 为料账乐观版本，从 1 开始。
 */
public record ReworkMaterialRow(
        Long id,
        String materialCode,
        Long productionRecordId,
        String sourceProductCode,
        Integer sourceFormulaVersion,
        List<String> allergens,
        BigDecimal availableKg,
        BigDecimal consumedKg,
        Integer version,
        Long registeredAt,
        Long updatedAt) {
}
