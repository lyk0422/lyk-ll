package com.example.starter.compliance.repo;

/**
 * 生产线行。
 *
 * @param lastProductCode    最近一次量产的产品编码，null 表示尚未量产
 * @param lastFormulaVersion 最近一次量产的配方版本，null 表示尚未量产
 * @param occupiedVersion    递增占用版本，从 1 开始，每次受控量产成功加 1
 */
public record ProductionLineRow(
        String lineCode,
        String lineName,
        String lastProductCode,
        Integer lastFormulaVersion,
        int occupiedVersion,
        long updatedAt) {
}
