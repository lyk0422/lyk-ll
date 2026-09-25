package com.example.starter.blast.domain;

import java.math.BigDecimal;

/**
 * 监测测点备案持久化对象。
 *
 * @param velocityLimitMmS 质点振速阈值，单位 mm/s
 */
public record MonitoringPoint(
        String zoneCode,
        String pointCode,
        String pointName,
        BigDecimal velocityLimitMmS) {
}
