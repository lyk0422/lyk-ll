package com.example.starter.ventilation.domain;

/**
 * 有序事件流水行，对应 event_log 表。
 *
 * @param id        自增顺序号
 * @param eventType 事件类型
 * @param sessionId 关联会话 id（可为空）
 * @param zoneCode  关联分区（可为空）
 * @param requestId 关联请求 id（可为空）
 * @param detail    事件细节
 * @param eventTime 事件时间 epoch 毫秒
 */
public record EventLogRow(long id, String eventType, Long sessionId, String zoneCode,
                          String requestId, String detail, long eventTime) {
}
