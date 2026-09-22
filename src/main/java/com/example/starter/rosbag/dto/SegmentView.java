package com.example.starter.rosbag.dto;

/**
 * 分段元数据视图。
 */
public record SegmentView(
        long id,
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
}
