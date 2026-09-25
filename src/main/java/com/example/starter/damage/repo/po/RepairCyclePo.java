package com.example.starter.damage.repo.po;

/** repair_cycle 行记录：修理链一环（首次修理或返修）。 */
public record RepairCyclePo(
        Long id,
        Long caseId,
        Integer attemptNo,
        Long estimateId,
        String approveRequestId,
        Long approvedAtUtc,
        Long validUntilUtc,
        String completeRequestId,
        Long completedAtUtc,
        String reinspectRequestId,
        Long reinspectedAtUtc,
        String reinspectResult,
        String status,
        Long createdAtUtc) {
}
