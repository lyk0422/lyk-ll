package com.example.starter.playout.web;

/**
 * 适配器 ACK 结果。
 *
 * @param channel         频道标识
 * @param ackKey          ACK 幂等键
 * @param asRunSequence   被确认的播出序号
 * @param accepted        是否实际接受确认；迟到 ACK 指向已被后续记录取代的状态时为 false
 * @param ackState        该序号当前 ACK 状态
 * @param idempotentReplay 是否为同键同参的重放
 */
public record AckResponse(
        String channel,
        String ackKey,
        long asRunSequence,
        boolean accepted,
        String ackState,
        boolean idempotentReplay) {
}
