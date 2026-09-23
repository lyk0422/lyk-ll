package com.example.starter.slam.domain;

import java.time.Instant;

/**
 * 关键帧记录。
 *
 * @param graphVersion 写入后所属的图版本（单位：单调递增整数）
 * @param createdAt 入库时间（UTC）
 */
public record Keyframe(
        String id,
        String source,
        int version,
        double x,
        double y,
        double theta,
        String measurementJson,
        String requestId,
        String contentHash,
        int graphVersion,
        Instant createdAt
) {
}
