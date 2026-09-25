package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 矿区。velocityThreshold 单位 cm/s；frozen 为冻结标记。
 */
public record Mine(
        long id,
        String name,
        BigDecimal velocityThreshold,
        boolean frozen,
        Instant frozenAt,
        String freezeReason) {
}
