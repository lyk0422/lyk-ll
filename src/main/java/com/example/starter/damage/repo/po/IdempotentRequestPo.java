package com.example.starter.damage.repo.po;

/** idempotent_request 行记录。 */
public record IdempotentRequestPo(
        String requestId,
        String operation,
        String fingerprint,
        String caseNo,
        Integer httpStatus,
        String responseJson,
        Long createdAtUtc) {
}
