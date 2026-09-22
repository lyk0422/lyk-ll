package com.example.starter.rosbag.dto;

/**
 * 查询命中的消息视图，按 (timestamp_ns, seq) 原顺序返回。
 */
public record MessageView(
        long id,
        String topic,
        long seq,
        long timestampNs,
        String typeId,
        String payload,
        long payloadChecksum,
        String batchNo,
        long segmentId
) {
}
