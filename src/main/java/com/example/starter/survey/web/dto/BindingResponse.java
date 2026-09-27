package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.BindingStatus;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.SurveyItem;

/** 互认绑定视图。 */
public record BindingResponse(
        Long id,
        Long surveyId,
        String reportNo,
        int reportRevision,
        int ruleVersion,
        SurveyItem itemCode,
        BindingStatus status,
        String createdAt,
        String invalidatedAt
) {

    /** 将领域记录转换为视图。 */
    public static BindingResponse from(RecognitionBinding binding) {
        return new BindingResponse(
                binding.id(), binding.surveyId(), binding.reportNo(),
                binding.reportRevision(), binding.ruleVersion(), binding.itemCode(),
                binding.status(), binding.createdAt().toString(),
                binding.invalidatedAt() == null ? null : binding.invalidatedAt().toString());
    }
}
