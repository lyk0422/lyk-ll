package com.example.starter.wb.domain;

/**
 * 集装器舱位主数据行（cargo_compartment）。
 * 力臂为整数毫米，限额为整数公斤（恰达合法）。
 */
public record CompartmentRow(
        long id,
        long aircraftId,
        String compartmentCode,
        int armMm,
        int maxLoadKg,
        String allowedUldTypes
) {
    /** 该舱位是否允许指定集装器类型（逗号分隔主数据）。 */
    public boolean allows(String uldType) {
        for (String t : allowedUldTypes.split(",")) {
            if (t.trim().equals(uldType)) {
                return true;
            }
        }
        return false;
    }
}
