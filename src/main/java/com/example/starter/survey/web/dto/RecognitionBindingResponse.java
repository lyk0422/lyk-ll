package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.RecognitionStatus;

/** 互认绑定视图。 */
public record RecognitionBindingResponse(
        Long id,
        Long surveyId,
        String reportNo,
        Integer reportRevision,
        Integer ruleVersion,
        String flagItemCode,
        String classItemCode,
        RecognitionStatus status,
        String createdAt,
        String invalidatedAt
) {

    /** 将领域记录转换为视图；报告编号/修订号与规则版本由调用方补充。 */
    public static RecognitionBindingResponse from(RecognitionBinding binding,
                                                  String reportNo, int reportRevision,
                                                  int ruleVersion) {
        return new RecognitionBindingResponse(
                binding.id(), binding.surveyId(), reportNo, reportRevision, ruleVersion,
                binding.flagItemCode(), binding.classItemCode(), binding.status(),
                binding.createdAt() == null ? null : binding.createdAt().toString(),
                binding.invalidatedAt() == null ? null : binding.invalidatedAt().toString());
    }
}
