package com.example.starter.wbb.po;

import java.time.LocalDateTime;

/**
 * 关闭后迟到事件持久化对象。
 */
public record LateEventPo(
        Long id,
        String flightKey,
        Long manifestId,
        String eventType,
        String detail,
        Boolean alarmed,
        LocalDateTime createdAt
) {
}
