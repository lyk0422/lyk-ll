package com.example.starter.evidence.repo;

import java.time.Instant;

import com.example.starter.evidence.domain.HoldStatus;

/**
 * 法律保全单表行。
 *
 * @param holdId           保全单标识
 * @param requestId        创建请求幂等键
 * @param fingerprint      创建请求参数指纹
 * @param caseId           案件编号
 * @param status           状态 ACTIVE/RELEASED
 * @param version          版本，创建 1，解除 2
 * @param createdAt        创建时间（UTC）
 * @param releasedAt       解除时间（UTC），可空
 * @param releaseRequestId 解除请求幂等键，可空
 */
public record HoldRow(
        String holdId,
        String requestId,
        String fingerprint,
        String caseId,
        HoldStatus status,
        int version,
        Instant createdAt,
        Instant releasedAt,
        String releaseRequestId) {
}
