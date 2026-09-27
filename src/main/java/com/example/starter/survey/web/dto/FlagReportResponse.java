package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagReport;

/** 报告视图（报告头 + 当前修订；existing=true 表示同参重复导入未新增）。 */
public record FlagReportResponse(
        Long id,
        String reportNo,
        String shipImo,
        String flagCode,
        Integer currentRevision,
        FlagReportRevisionResponse revision,
        boolean existing
) {

    public static FlagReportResponse from(FlagReport report, FlagReportRevisionResponse revision,
                                          boolean existing) {
        return new FlagReportResponse(report.id(), report.reportNo(), report.shipImo(),
                report.flagCode(), report.currentRevision(), revision, existing);
    }
}
