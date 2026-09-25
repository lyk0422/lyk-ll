package com.example.starter.ili.dto;

import java.math.BigDecimal;

/**
 * 缺陷读数视图，用于“新发现”“已消失”清单。
 *
 * @param defectRef    检测方缺陷编号
 * @param absPositionM 缺陷绝对里程，单位米
 * @param depthPct     缺陷深度，占壁厚百分比
 */
public record ReadingView(
        String defectRef,
        BigDecimal absPositionM,
        BigDecimal depthPct) {
}
