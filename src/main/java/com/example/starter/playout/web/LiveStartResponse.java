package com.example.starter.playout.web;

import java.time.Instant;

/**
 * 直播插播开始结果。
 *
 * @param channel       频道标识
 * @param requestId     请求幂等键
 * @param priority      优先级
 * @param start         开始时间(UTC)
 * @param firstSequence 该插播首条 LIVE 播出记录序号（尚未经 tick 生成时为 null）
 * @param idempotentReplay 是否为同键同参的重放（返回首次冻结结果）
 */
public record LiveStartResponse(
        String channel,
        String requestId,
        int priority,
        Instant start,
        Long firstSequence,
        boolean idempotentReplay) {
}
