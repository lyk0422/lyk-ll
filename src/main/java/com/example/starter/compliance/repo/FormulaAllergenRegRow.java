package com.example.starter.compliance.repo;

import java.util.List;

/**
 * 配方版本过敏原登记行。
 *
 * @param version    乐观变更版本，登记时为 1，每次修改成功 +1
 * @param allergens  已登记过敏原编码集合（升序）
 */
public record FormulaAllergenRegRow(
        long formulaId,
        int version,
        String requestId,
        long createdAt,
        long updatedAt,
        List<String> allergens) {
}
