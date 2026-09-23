package com.example.starter.playout.domain;

import java.time.Instant;

/**
 * as-run 播出日志行；channelSeq 即对外的 asRunSequence，频道内单调递增。
 */
public record AsRunRow(
        long id,
        String channel,
        long channelSeq,
        Long versionId,
        String kind,
        String itemId,
        String materialDigest,
        String liveRequestId,
        Integer priority,
        Instant startTime,
        Instant endTime,
        String ackState,
        Instant createdAt) {
}
