package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 提交登轮检验记录。
 *
 * @param completedDate 检验完成日（UTC），须落在窗口首日至末日（含展期末日）内
 * @param defects       本次发现的缺陷列表（可为空）
 */
public record BoardSurveyRequest(
        @NotBlank String requestId,
        @NotNull Long surveyId,
        @NotNull LocalDate completedDate,
        @NotNull @Valid List<DefectInput> defects
) {
}
