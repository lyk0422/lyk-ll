package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 检验过程事件（如签发后迟到的检验记录仅登记为迟到事件，不改变终态）。
 *
 * @param id         主键
 * @param surveyId   关联检验
 * @param eventType  事件类型，如 LATE_BOARD_RECORD
 * @param payload    事件内容 JSON
 * @param occurredAt 事件发生时刻（UTC）
 */
public record SurveyEvent(
        Long id,
        Long surveyId,
        String eventType,
        String payload,
        Instant occurredAt
) {
}
