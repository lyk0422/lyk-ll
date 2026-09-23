package com.example.starter.po;

import java.time.Instant;

/**
 * 审计记录持久化对象，对应 audit_record 表。
 *
 * @param id          自增主键
 * @param eventType   事件类型（AuditType 名称）
 * @param deviceId    相关设备标识，可为 null
 * @param topic       相关主题，可为 null
 * @param seq         相关设备本地序号，可为 null
 * @param dedupKey    相关去重键，可为 null
 * @param messageId   相关消息主键，可为 null
 * @param reason      事件原因说明
 * @param createdAt   事件时间
 */
public record AuditRecord(Long id, String eventType, String deviceId, String topic, Long seq,
                          String dedupKey, Long messageId, String reason, Instant createdAt) {
}
