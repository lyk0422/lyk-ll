package com.example.starter.bag.web.dto;

import java.util.List;

/**
 * 回放结果。duplicated 为 true 时表示复用了既有回放记录，消息仍按原顺序返回。
 */
public record PlayResult(
        long playId,
        String playKey,
        String topic,
        long messageCount,
        String replayChecksum,
        boolean duplicated,
        List<MessageView> messages) {
}
