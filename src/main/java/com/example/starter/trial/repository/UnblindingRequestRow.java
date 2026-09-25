package com.example.starter.trial.repository;

import java.time.Instant;

/**
 * unblinding_request 行记录。时间为 null 表示对应步骤尚未发生。
 */
public record UnblindingRequestRow(
        long id,
        String requestId,
        String trialId,
        String subjectScreenNo,
        String status,
        String confirmerA,
        Instant confirmedAAtUtc,
        String confirmerB,
        Instant confirmedBAtUtc,
        Instant completedAtUtc,
        Instant createdAtUtc) {
}
