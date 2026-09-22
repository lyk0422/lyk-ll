package com.example.starter.ros.web.dto;

import java.util.List;

/**
 * 事件拉取响应:当前批次事件及订阅方最新水位。
 */
public record EventBatchResponse(
        String subscriberId,
        long lastRevision,
        List<EventView> events
) {
}
