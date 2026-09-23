package com.example.starter.evidence.repo;

import java.time.Instant;

import com.example.starter.evidence.domain.CustodyEventType;

/**
 * 保管事件表行，仅追加不可变。
 *
 * @param id         事件自增主键
 * @param evidenceId 关联证据标识
 * @param eventType  事件类型
 * @param detail     补充说明，可空
 * @param occurredAt 发生时间（UTC）
 */
public record CustodyEventRow(
        long id,
        String evidenceId,
        CustodyEventType eventType,
        String detail,
        Instant occurredAt) {
}
