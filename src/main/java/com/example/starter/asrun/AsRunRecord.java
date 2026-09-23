package com.example.starter.asrun;

/**
 * as-run 播出记录视图：半开时段 [startMs, endMs)，endMs 为空表示在播。
 * status：OPEN 在播 / CLOSED 已结束 / SUPERSEDED 被后续记录取代。
 */
public record AsRunRecord(
        String channelId,
        long seq,
        String kind,
        String itemId,
        String mediaDigest,
        String breakRequestId,
        long startMs,
        Long endMs,
        String status,
        boolean acked,
        Long ackedAtMs
) {
}
