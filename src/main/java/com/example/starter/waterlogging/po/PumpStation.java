package com.example.starter.waterlogging.po;

import java.math.BigDecimal;

/**
 * 泵站台账 pump_station 映射。
 *
 * @param safetyLowLevel 安全下限水位（米），低于该值立即停泵，恰等合法
 * @param runStatus      RUNNING / STOPPED
 * @param gateOpening    当前闸门开度（百分比）
 */
public record PumpStation(
        Long id,
        String stationCode,
        String stationName,
        String basinZone,
        BigDecimal safetyLowLevel,
        String runStatus,
        BigDecimal gateOpening) {
}
