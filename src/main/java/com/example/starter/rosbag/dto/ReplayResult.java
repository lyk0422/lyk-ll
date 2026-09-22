package com.example.starter.rosbag.dto;

import java.util.List;

/**
 * 回放结果。deduplicated=true 表示回放记录已存在，消息按原顺序重新返回但不新建记录。
 */
public record ReplayResult(
        String replayKey,
        boolean deduplicated,
        long messageCount,
        List<MessageView> messages
) {
}
