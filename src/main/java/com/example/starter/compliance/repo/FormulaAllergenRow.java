package com.example.starter.compliance.repo;

import java.util.List;

/**
 * 配方版本过敏原登记行。
 *
 * @param allergens      过敏原编码集合（有序、去重）
 * @param version        登记乐观版本，从 1 开始，成功变更加 1
 * @param registeredAt   登记时刻（UTC epoch 毫秒，请求携带的精确时刻）
 */
public record FormulaAllergenRow(
        Long id,
        String productCode,
        Integer formulaVersion,
        List<String> allergens,
        int version,
        long registeredAt,
        long updatedAt) {
}
