package com.example.starter.ctlog.domain;

import java.time.Instant;

/**
 * 日志叶条目。
 *
 * @param leafIndex 叶索引，从 0 开始唯一连续
 * @param requestId 客户端幂等键
 * @param entryHash DER 字节的 SHA-256 摘要（即 Merkle 叶数据）
 * @param createdAt 提交时间
 */
public record LeafEntry(long leafIndex, String requestId, byte[] entryHash, Instant createdAt) {
}
