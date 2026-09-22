package com.example.starter.rosbag.model;

/**
 * 消息持久化对象。
 *
 * @param valid true=完整可查；false=恢复时被隔离的不完整后缀消息
 */
public record Message(
        Long id,
        String topic,
        long seq,
        long timestampNs,
        String typeId,
        String payload,
        long payloadChecksum,
        String batchNo,
        long segmentId,
        boolean valid
) {
}
