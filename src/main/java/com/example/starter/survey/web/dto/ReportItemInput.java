package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.ItemConclusion;
import com.example.starter.survey.domain.SurveyItem;
import jakarta.validation.constraints.NotNull;

/** 外部检查报告逐项结论输入。 */
public record ReportItemInput(
        @NotNull SurveyItem itemCode,
        @NotNull ItemConclusion conclusion
) {
}
