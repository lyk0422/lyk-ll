package com.example.starter.rosbag.model;

/**
 * 逻辑分段持久化对象。
 *
 * @param status OPEN=可追加；SEALED=正常封段；RECOVERED=经恢复重建后封段
 */
public record Segment(
        Long id,
        String topic,
        long segmentNo,
        Long startNs,
        Long endNs,
        Long startSeq,
        Long endSeq,
        long messageCount,
        String status,
        long segmentChecksum
) {
    public static final String OPEN = "OPEN";
    public static final String SEALED = "SEALED";
    public static final String RECOVERED = "RECOVERED";
}
