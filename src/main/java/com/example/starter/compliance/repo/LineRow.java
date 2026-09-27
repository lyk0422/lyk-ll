package com.example.starter.compliance.repo;

/**
 * 生产线行。
 *
 * @param lastFormulaId 最近一次量产配方版本主键，null 表示尚未量产
 * @param occupancyVersion 递增占用版本，从 1 开始，每笔受控量产成功 +1
 */
public record LineRow(
        String lineCode,
        Long lastFormulaId,
        int occupancyVersion,
        long createdAt,
        long updatedAt) {
}
