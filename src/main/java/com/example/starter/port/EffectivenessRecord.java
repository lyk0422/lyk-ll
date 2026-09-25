package com.example.starter.port;

import java.time.Instant;

/**
 * 生效记录（effectiveness_record 表行映射）；同一流程只产生一条。
 */
public record EffectivenessRecord(
        long id,
        long portRequestId,
        String requestId,
        String fingerprint,
        String msisdn,
        Instant effectiveAt) {
}
