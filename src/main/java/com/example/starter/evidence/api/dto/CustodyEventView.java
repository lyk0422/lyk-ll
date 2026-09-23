package com.example.starter.evidence.api.dto;

import java.time.Instant;

/**
 * 保管事件视图。
 *
 * @param id         事件自增主键
 * @param eventType  事件类型
 * @param detail     补充说明，可空
 * @param occurredAt 发生时间（UTC）
 */
public record CustodyEventView(long id, String eventType, String detail, Instant occurredAt) {
}
