package com.example.starter.ros;

import java.time.Instant;

/**
 * 事件流中的一条事务事件，游标单调递增。
 *
 * @param id            单调游标
 * @param transactionId 产生该事件的事务 ID
 * @param eventType     事件类型，如 PARAMETERS_COMMITTED
 * @param payloadJson   事件负载（各节点新版本摘要的 JSON）
 * @param createdAt     事件落库时间（UTC）
 */
public record EventRecord(
        long id,
        String transactionId,
        String eventType,
        String payloadJson,
        Instant createdAt) {
}
