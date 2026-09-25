package com.example.starter.wbb.dto;

/**
 * 迟到事件响应：舱单关闭后收到的值机/LMC 变化只记录事件并告警，不改舱单。
 */
public record LateEventResponse(
        Long id,
        String flightKey,
        Long manifestId,
        String eventType,
        String detail,
        Boolean alarmed,
        String createdAt
) {
}
