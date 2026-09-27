package com.example.starter.wb.domain;

import java.util.List;

/**
 * 集装器舱位主数据（cargo_compartment）。
 * 力臂为整数毫米，最大载荷为整数公斤（含皮重，恰达合法）。
 */
public record CompartmentRow(
        Long id,
        long aircraftId,
        String compartmentCode,
        int armMm,
        int maxLoadKg,
        List<String> allowedUldTypes
) {
}
