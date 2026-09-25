package com.example.starter.survey.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 窗口台账行：由船舶基准日、检验记录与展期记录复算生成，可随时重建。
 *
 * @param id             主键
 * @param shipId         船舶主键
 * @param type           检验类型
 * @param cycleAnchor    周期锚点日（UTC）
 * @param windowStart    窗口首日（含，UTC）
 * @param windowEnd      窗口末日（含，UTC）
 * @param extensionUntil 展期后末日（含，UTC），未展期为 null
 * @param surveyId       已关联的检验，无则 null
 * @param computedAt     本行复算时刻（UTC）
 */
public record WindowLedgerRow(
        Long id,
        Long shipId,
        SurveyType type,
        LocalDate cycleAnchor,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate extensionUntil,
        Long surveyId,
        Instant computedAt
) {
}
