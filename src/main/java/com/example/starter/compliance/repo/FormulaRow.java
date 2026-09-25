package com.example.starter.compliance.repo;

/**
 * 配方主表行（不含明细，明细单独查询）。
 */
public record FormulaRow(
        Long id,
        String productCode,
        String productName,
        String categoryCode,
        Integer version,
        String state,
        String requestId,
        Boolean reviewPassed,
        String reviewJson,
        Long reviewedAt,
        String approvedSnapshot,
        Long approvedAt,
        long createdAt) {
}
