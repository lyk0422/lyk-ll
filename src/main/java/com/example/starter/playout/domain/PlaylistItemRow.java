package com.example.starter.playout.domain;

import java.time.Instant;

/**
 * 播单节目项行；半开时段 [startTime,endTime)，端点相接合法。
 */
public record PlaylistItemRow(
        long id,
        long versionId,
        String itemId,
        String materialDigest,
        Instant startTime,
        Instant endTime,
        int seqNo) {
}
