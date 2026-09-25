package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 爆区。maxChargePerDelay 为备案单段药量上限，单位 kg。
 */
public record BlastZone(
        long id,
        long mineId,
        String name,
        BigDecimal maxChargePerDelay) {
}
