package com.example.starter.orchestrator.dto;

import java.time.Instant;

/**
 * 事件视图。
 *
 * @param id 事件自增主键
 * @param nodeKey 关联节点标识，计划级事件为 null
 * @param eventType 事件类型
 * @param detail 事件补充说明
 * @param createdAt 事件时间
 */
public record EventView(long id, String nodeKey, String eventType, String detail, Instant createdAt) {
}
