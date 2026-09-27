package com.example.starter.survey.domain;

/**
 * 报告修订的逐项结论。
 *
 * @param id         主键
 * @param revisionId 所属修订主键
 * @param itemCode   船旗检查项目代码
 * @param conclusion 逐项结论
 */
public record FlagReportItem(
        Long id,
        Long revisionId,
        String itemCode,
        ItemConclusion conclusion
) {
}
