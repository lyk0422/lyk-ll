package com.example.starter.wbb.po;

import java.math.BigDecimal;

/**
 * 重心包线折点持久化对象。
 */
public record EnvelopePointPo(
        Long id,
        Long configId,
        Integer seqNo,
        BigDecimal weightKg,
        BigDecimal forwardArm,
        BigDecimal aftArm
) {
}
