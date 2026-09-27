package com.example.starter.compliance.repo;

/**
 * 配方版本过敏原登记行。
 *
 * @param version       登记自身的乐观锁版本：首次登记为 1，每次修改成功 +1
 * @param allergenCodes 过敏原编码结构化 JSON 数组（编码区分大小写，不靠分隔符拼接）
 * @param updatedAt     最近一次登记/修改的 UTC epoch 毫秒
 */
public record FormulaAllergenRow(
        Long formulaId,
        Integer version,
        String allergenCodes,
        String requestId,
        Long updatedAt) {
}
