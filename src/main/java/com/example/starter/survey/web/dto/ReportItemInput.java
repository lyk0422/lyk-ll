package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.ItemConclusion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 报告逐项结论输入。 */
public record ReportItemInput(
        @NotBlank String itemCode,
        @NotNull ItemConclusion conclusion
) {
}
