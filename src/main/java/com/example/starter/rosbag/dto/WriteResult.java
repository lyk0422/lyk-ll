package com.example.starter.rosbag.dto;

/**
 * 写入结果。deduplicated=true 表示该批次已存在，本次为重试，未重复写入。
 */
public record WriteResult(
        String topic,
        long seq,
        long segmentId,
        long segmentNo,
        String batchNo,
        long payloadChecksum,
        boolean deduplicated
) {
}
