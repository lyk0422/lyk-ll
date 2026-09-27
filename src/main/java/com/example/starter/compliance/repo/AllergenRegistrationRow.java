package com.example.starter.compliance.repo;

/**
 * 配方版本过敏原登记行（主表）；过敏原集合明细见 formula_allergen_item。
 *
 * @param version 登记版本，从 1 开始，每次成功变更 +1
 */
public record AllergenRegistrationRow(
        long formulaId,
        int version,
        long updatedAt) {
}
