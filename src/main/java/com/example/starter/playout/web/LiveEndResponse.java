package com.example.starter.playout.web;

import java.time.Instant;

/**
 * 直播插播结束结果。
 *
 * @param channel          频道标识
 * @param requestId        请求幂等键
 * @param end              结束时间(UTC)
 * @param endKind          ENDED-正常结束 / PREEMPTED-此前已被更高优先级抢占
 * @param idempotentReplay 是否为同键同参的重放
 */
public record LiveEndResponse(
        String channel,
        String requestId,
        Instant end,
        String endKind,
        boolean idempotentReplay) {
}
