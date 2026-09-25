package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.domain.SurveyType;

/** 检验视图。 */
public record SurveyResponse(
        Long id,
        String requestId,
        String shipImo,
        SurveyType type,
        SurveyStatus status,
        String cycleAnchor,
        String windowStart,
        String windowEnd,
        String extensionUntil,
        String appliedAt,
        String boardedAt,
        String certifiedAt
) {

    /** 将领域记录转换为视图；{@code shipImo} 由调用方补充。 */
    public static SurveyResponse from(Survey survey, String shipImo) {
        return new SurveyResponse(
                survey.id(), survey.requestId(), shipImo, survey.type(), survey.status(),
                survey.cycleAnchor() == null ? null : survey.cycleAnchor().toString(),
                survey.windowStart() == null ? null : survey.windowStart().toString(),
                survey.windowEnd() == null ? null : survey.windowEnd().toString(),
                survey.extensionUntil() == null ? null : survey.extensionUntil().toString(),
                survey.appliedAt() == null ? null : survey.appliedAt().toString(),
                survey.boardedAt() == null ? null : survey.boardedAt().toString(),
                survey.certifiedAt() == null ? null : survey.certifiedAt().toString()
        );
    }
}
