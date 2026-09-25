package com.example.starter.fishery.db;

import java.time.Instant;

/**
 * 幂等请求台账行；指纹覆盖 requestId 与全部业务字段。
 *
 * @param fingerprint 业务参数指纹（SHA-256 十六进制）
 * @param responseJson 首次成功结果快照（JSON），同键同参重放返回
 */
public record IdempotencyRow(
        String requestId,
        String operation,
        String fingerprint,
        String responseJson,
        Instant createdAt) {
}
