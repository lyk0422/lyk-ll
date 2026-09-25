package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.DefectSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 登轮检验时登记的缺陷项。 */
public record DefectInput(
        @NotNull DefectSeverity severity,
        @NotBlank String description,
        @NotNull LocalDate foundDate
) {
}
