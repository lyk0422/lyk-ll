package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.ItemResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 外部报告的逐项结论。
 */
public record ReportItemInput(
        @NotBlank String itemCode,
        @NotNull ItemResult result,
        String note
) {
}
