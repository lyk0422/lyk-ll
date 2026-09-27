package com.example.starter.compliance.repo;

/**
 * 生产线行。
 *
 * @param lastFormulaId    最近一次受控量产受理的批准配方版本（NULL 表示登记后尚无量产）
 * @param occupiedVersion  递增占用版本，登记时为 1，每受理成功一笔受控量产 +1
 * @param version          乐观变更版本，登记时为 1，每次成功变更加 1
 */
public record ProductionLineRow(
        String lineCode,
        String lineName,
        Long lastFormulaId,
        int occupiedVersion,
        int version,
        long createdAt,
        long updatedAt) {
}
