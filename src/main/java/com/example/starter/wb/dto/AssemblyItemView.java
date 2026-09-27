package com.example.starter.wb.dto;

import java.math.BigDecimal;

/**
 * 装配图明细对外视图。
 *
 * @param totalKg    总重=皮重+净重，kg
 * @param armMm      舱位力臂快照，整数毫米
 * @param momentKgMm 力矩=总重×力臂，kg·mm
 */
public record AssemblyItemView(
        String uldNumber,
        String uldType,
        String compartmentCode,
        int tareKg,
        int netKg,
        int totalKg,
        int armMm,
        BigDecimal momentKgMm
) {
}
