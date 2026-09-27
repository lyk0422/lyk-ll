package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagItemConclusion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 外部检查逐项结论输入。 */
public record FlagReportItemInput(
        @NotBlank String flagItemCode,
        @NotNull FlagItemConclusion conclusion
) {
}
