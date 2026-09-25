package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 提交缺陷整改复查；submitDate 为整改材料提交日（UTC）。 */
public record RectifyDefectRequest(
        @NotBlank String requestId,
        @NotNull Long defectId,
        @NotNull LocalDate submitDate,
        String note
) {
}
