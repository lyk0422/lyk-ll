package com.example.starter.compliance.repo;

/**
 * 生产线行。
 *
 * @param lastFormulaId    最近一次受控量产的配方版本 ID；NULL 表示登记后尚无生产（首产无需清线凭证）
 * @param occupancyVersion 递增占用版本：登记时为 1，每笔受控量产成功原子 +1；
 *                         受控量产以 expectedVersion 条件更新，并发同版本仅一笔成功
 */
public record ProductionLineRow(
        String lineCode,
        String lineName,
        Long lastFormulaId,
        Integer occupancyVersion,
        Long createdAt,
        Long updatedAt) {
}
