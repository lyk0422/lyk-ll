package com.example.starter.evidence.repo;

import java.time.Instant;

import com.example.starter.evidence.domain.PurgeBatchStatus;

/**
 * 清除批次表行。
 *
 * @param batchId            批次标识
 * @param requestId          标记请求幂等键
 * @param fingerprint        标记请求参数指纹
 * @param cutoff             到期截止时间（UTC）
 * @param expectedVersion    标记请求预期领域版本，可空
 * @param status             状态 MARKED/CONFIRMED
 * @param version            批次版本，标记 1，确认 2
 * @param manifestJson       确认时冻结的不可变清单 JSON，可空
 * @param confirmRequestId   确认请求幂等键，可空
 * @param confirmFingerprint 确认请求参数指纹，可空
 * @param createdAt          标记时间（UTC）
 * @param confirmedAt        确认时间（UTC），可空
 */
public record PurgeBatchRow(
        String batchId,
        String requestId,
        String fingerprint,
        Instant cutoff,
        Long expectedVersion,
        PurgeBatchStatus status,
        int version,
        String manifestJson,
        String confirmRequestId,
        String confirmFingerprint,
        Instant createdAt,
        Instant confirmedAt) {
}
