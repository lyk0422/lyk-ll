package com.example.starter.wb.domain;

import java.math.BigDecimal;

/**
 * 业载站位：站位代码与力臂（站位英寸）。
 */
public record LoadStation(
        String stationCode,
        BigDecimal arm,
        String label
) {
}
