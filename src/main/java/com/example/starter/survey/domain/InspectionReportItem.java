package com.example.starter.survey.domain;

/**
 * 报告逐项结论。
 *
 * @param id         主键
 * @param reportId   所属报告修订主键
 * @param itemCode   船级检查项目代码
 * @param conclusion 结论（仅 PASS 可作为互认合格项目）
 */
public record InspectionReportItem(
        Long id,
        Long reportId,
        SurveyItem itemCode,
        ItemConclusion conclusion
) {
}
