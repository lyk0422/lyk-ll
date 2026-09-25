package com.example.starter.damage.repo.po;

/** case_event 行记录：案件事件流水。 */
public record CaseEventPo(
        Long id,
        Long caseId,
        String eventType,
        String payload,
        Long createdAtUtc) {
}
