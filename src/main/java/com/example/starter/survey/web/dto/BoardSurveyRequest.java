package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.SurveyItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 提交登轮检验记录。
 *
 * @param completedDate  检验完成日（UTC），须落在窗口首日至末日（含展期末日）内
 * @param defects        本次发现的缺陷列表（可为空）
 * @param completedItems 本次登轮实际完成的项目集合；为 null 表示该类型全部必需项目均已完成
 *                       （兼容旧调用）；显式列表（可为空，表示全部依赖互认覆盖）
 *                       须为必需项目子集且无重复
 */
public record BoardSurveyRequest(
        @NotBlank String requestId,
        @NotNull Long surveyId,
        @NotNull LocalDate completedDate,
        @NotNull @Valid List<DefectInput> defects,
        @Valid List<SurveyItem> completedItems
) {
}
