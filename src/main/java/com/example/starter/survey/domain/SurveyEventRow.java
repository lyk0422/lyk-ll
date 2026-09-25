package com.example.starter.survey.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 检验事件行：申请/登轮/缺陷发现/整改/复查/发证/迟到记录，事件链可复算。
 */
public record SurveyEventRow(
        Long id,
        Long surveyId,
        String eventType,
        LocalDate eventDate,
        String detail,
        LocalDateTime createdAt) {
}
