package com.example.starter.web.dto;

/**
 * 复开申请响应。
 *
 * @param eventId 管制事件 id
 * @param status  事件状态
 */
public record ReopenApplicationResponse(long eventId, String status) {
}
