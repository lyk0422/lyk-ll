package com.example.starter.specimen.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 分装行：由分装操作生成，携带状态、当前保管方与乐观锁版本。
 */
public record AliquotRow(
        String aliquotId,
        String specimenId,
        BigDecimal volumeUl,
        AliquotStatus status,
        String custodian,
        long version,
        Instant lastEventAt,
        Instant createdAt) {
}
