package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagReportItem;

/** 报告逐项结论视图。 */
public record FlagReportItemView(
        String itemCode,
        String conclusion
) {

    public static FlagReportItemView from(FlagReportItem item) {
        return new FlagReportItemView(item.itemCode(), item.conclusion().name());
    }
}
