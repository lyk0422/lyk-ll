package com.example.starter.specimen.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 原始样本行：体积单位微升（三位小数），remainingVolumeUl 与全部分装体积之和精确守恒。
 */
public record SpecimenRow(
        String specimenId,
        BigDecimal totalVolumeUl,
        BigDecimal remainingVolumeUl,
        String custodian,
        long version,
        Instant lastEventAt,
        Instant createdAt) {
}
