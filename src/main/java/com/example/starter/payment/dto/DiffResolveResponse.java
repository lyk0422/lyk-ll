package com.example.starter.payment.dto;

import java.time.Instant;

/**
 * 差异核销结果；replayed=true 表示该差异此前已处理，返回首次处理留痕而不重复核销。
 */
public record DiffResolveResponse(
        Long diffId,
        String channelSerialNo,
        String diffType,
        String action,
        String status,
        boolean replayed,
        Instant resolvedAt) {
}
