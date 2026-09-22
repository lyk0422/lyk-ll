package com.example.starter.rosbag.model;

/**
 * 回放记录持久化对象，相同 replayKey 只能存在一条。
 */
public record ReplayRecord(
        Long id,
        String replayKey,
        String topic,
        Long fromNs,
        Long toNs,
        long messageCount,
        long createdAtNs
) {
}
