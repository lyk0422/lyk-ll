package com.example.starter.evidence.repo;

import java.time.Instant;

import com.example.starter.evidence.domain.EvidenceStatus;

/**
 * 证据登记表行。
 *
 * @param evidenceId  证据唯一标识
 * @param sha256      证据内容 SHA-256 摘要（小写十六进制）
 * @param collectedAt 采集时间（UTC）
 * @param expiresAt   到期时间（UTC）
 * @param status      生命周期状态
 * @param version     乐观锁版本
 * @param createdAt   登记时间（UTC）
 * @param updatedAt   最近变更时间（UTC）
 */
public record EvidenceRow(
        String evidenceId,
        String sha256,
        Instant collectedAt,
        Instant expiresAt,
        EvidenceStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
