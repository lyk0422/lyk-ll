package com.example.starter.ros.web.dto;

import java.time.LocalDateTime;

/**
 * 资源变更事件视图,订阅方按 revision 递增消费。
 */
public record EventView(
        long revision,
        String nodeId,
        String eventType,
        String payload,
        LocalDateTime createdAt
) {
}
