package com.example.starter.evidence.api.dto;

import java.time.Instant;

/**
 * 证据视图。
 *
 * @param evidenceId  证据唯一标识
 * @param sha256      证据内容 SHA-256 摘要
 * @param collectedAt 采集时间（UTC）
 * @param expiresAt   到期时间（UTC）
 * @param status      生命周期状态 ACTIVE/MARKED/PURGED
 * @param version     乐观锁版本
 * @param replayed    本次请求是否为幂等重放
 */
public record EvidenceView(
        String evidenceId,
        String sha256,
        Instant collectedAt,
        Instant expiresAt,
        String status,
        int version,
        boolean replayed) {
}
