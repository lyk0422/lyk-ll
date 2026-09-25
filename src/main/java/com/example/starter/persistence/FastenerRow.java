package com.example.starter.persistence;

import java.math.BigDecimal;

/**
 * 紧固件（紧固点）工艺行，力矩单位 N·m。
 */
public record FastenerRow(
        long id,
        long cardId,
        String pointCode,
        BigDecimal nominalTorque,
        BigDecimal tolerancePct) {
}
