package com.example.starter.portability;

import java.time.Instant;

/**
 * 生效记录实体，对应 effective_record 表；同一流程至多一条（flow_id 唯一约束）。
 */
public record EffectiveRecord(
        Long id,
        Long flowId,
        String msisdn,
        String broadcastRequestId,
        Instant effectiveAt) {
}
