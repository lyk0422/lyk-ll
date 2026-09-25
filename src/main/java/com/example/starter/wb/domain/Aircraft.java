package com.example.starter.wb.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合成飞机及其包线、站位主数据。
 * 重量单位 kg，力臂为站位英寸。
 */
public record Aircraft(
        long id,
        String tailNo,
        String aircraftType,
        int oewKg,
        BigDecimal oewArm,
        int mzfwKg,
        int mtowKg,
        int mlwKg,
        BigDecimal lemac,
        BigDecimal macLength,
        BigDecimal fuelArm,
        List<EnvelopePoint> envelope,
        List<LoadStation> stations
) {
}
