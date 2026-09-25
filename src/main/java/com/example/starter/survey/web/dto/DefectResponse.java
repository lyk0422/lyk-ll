package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.Defect;
import com.example.starter.survey.domain.DefectSeverity;
import com.example.starter.survey.domain.DefectStatus;

/** 缺陷视图。 */
public record DefectResponse(
        Long id,
        Long surveyId,
        DefectSeverity severity,
        String description,
        String foundDate,
        DefectStatus status,
        String deadline,
        String submittedAt,
        String reviewedAt,
        String note
) {

    public static DefectResponse from(Defect defect) {
        return new DefectResponse(
                defect.id(), defect.surveyId(), defect.severity(), defect.description(),
                defect.foundDate().toString(), defect.status(),
                defect.deadline() == null ? null : defect.deadline().toString(),
                defect.submittedAt() == null ? null : defect.submittedAt().toString(),
                defect.reviewedAt() == null ? null : defect.reviewedAt().toString(),
                defect.note()
        );
    }
}
