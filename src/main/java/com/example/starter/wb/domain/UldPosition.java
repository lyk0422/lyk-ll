package com.example.starter.wb.domain;

/**
 * 集装器舱位主数据（uld_position）。
 *
 * @param positionCode   舱位代码
 * @param armMm          舱位力臂，整数毫米（合成口径，力矩精确求和用）
 * @param maxPayloadKg   允许集装器最大总重（皮重+净重），整数 kg；恰达合法
 * @param allowedUldTypes 允许集装器类型代码
 */
public record UldPosition(
        long id,
        long aircraftId,
        String positionCode,
        int armMm,
        int maxPayloadKg,
        java.util.List<String> allowedUldTypes
) {
}
