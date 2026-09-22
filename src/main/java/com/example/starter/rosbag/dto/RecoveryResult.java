package com.example.starter.rosbag.dto;

import java.util.List;

/**
 * 恢复结果：保留前缀、隔离尾部消息、删除并重建索引的统计。
 */
public record RecoveryResult(
        String topic,
        long segmentsScanned,
        long prefixMessagesKept,
        long incompleteMessagesQuarantined,
        long indexRowsRebuilt,
        List<SegmentView> segments
) {
}
