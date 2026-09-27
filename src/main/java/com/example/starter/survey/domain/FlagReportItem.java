package com.example.starter.survey.domain;

/**
 * 报告逐项结论。
 *
 * @param id           主键
 * @param reportId     所属报告修订主键
 * @param flagItemCode 船旗检查项目代码
 * @param conclusion   逐项结论（重大缺陷项目不得充当合格证据）
 */
public record FlagReportItem(
        Long id,
        Long reportId,
        String flagItemCode,
        FlagItemConclusion conclusion
) {
}
