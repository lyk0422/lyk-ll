package com.example.starter.survey.domain;

/**
 * 外部报告的逐项检查结论。
 *
 * @param id        主键
 * @param reportId  所属报告修订行
 * @param itemCode  船旗国检查项目代码
 * @param result    PASS / FAIL / MAJOR_DEFECT
 * @param note      备注（可为空）
 */
public record ExternalReportItem(
        Long id,
        Long reportId,
        String itemCode,
        ItemResult result,
        String note
) {
}
