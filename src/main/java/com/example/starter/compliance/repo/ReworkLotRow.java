package com.example.starter.compliance.repo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 返工料账行：登记时冻结来源配方版本与过敏原集合；
 * availableKg 为独立合成额度（不对应旧记录既有产量），consumedKg 为累计消费；
 * version 为料账乐观版本，从 1 开始，每次成功消费加 1。
 */
public record ReworkLotRow(
        Long id,
        String lotCode,
        Long productionRecordId,
        String productCode,
        Integer formulaVersion,
        List<String> allergens,
        BigDecimal availableKg,
        BigDecimal consumedKg,
        Integer version,
        long registeredAt,
        long updatedAt) {

    /** 当前余额（availableKg - consumedKg），单位公斤。 */
    public BigDecimal balanceKg() {
        return availableKg.subtract(consumedKg);
    }
}
