package com.example.starter.damage.repo.po;

/** survey 行记录。 */
public record SurveyPo(
        Long id,
        Long caseId,
        String requestId,
        String surveyor,
        Long surveyedAtUtc,
        Boolean lateEvent,
        Long createdAtUtc) {
}
