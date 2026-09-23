package com.example.starter.ros;

import java.time.Instant;

/**
 * 已落库的事务记录，用于幂等重放与同键异参冲突判定。
 *
 * @param transactionId 事务 ID（唯一）
 * @param requestHash   请求规范化内容的 SHA-256；同 ID 不同哈希即 409
 * @param status        原事务最终状态
 * @param responseJson  原响应序列化 JSON，同键同参原样返回
 * @param createdAt     首次处理时间（UTC）
 */
public record TransactionRecord(
        String transactionId,
        String requestHash,
        TransactionStatus status,
        String responseJson,
        Instant createdAt) {
}
