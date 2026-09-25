package com.example.starter.blast.domain;

import java.math.BigDecimal;

/**
 * 爆区备案持久化对象。
 *
 * @param maxChargePerDelayKg 备案单段最大药量，单位 kg
 */
public record BlastZone(
        String zoneCode,
        String mineCode,
        String zoneName,
        BigDecimal maxChargePerDelayKg) {
}
