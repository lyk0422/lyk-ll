package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagInspectionReport;
import com.example.starter.survey.domain.FlagReportItem;

import java.util.List;

/** 外部检查报告修订视图（含逐项结论）。 */
public record FlagReportResponse(
        Long id,
        String reportNo,
        int revision,
        String imo,
        String flagCode,
        String completedAt,
        String syntheticSignoff,
        List<FlagReportItemInput> items,
        String createdAt
) {

    /** 将领域记录与逐项结论转换为视图。 */
    public static FlagReportResponse from(FlagInspectionReport report, List<FlagReportItem> items) {
        return new FlagReportResponse(
                report.id(), report.reportNo(), report.revision(), report.imo(), report.flagCode(),
                report.completedAt() == null ? null : report.completedAt().toString(),
                report.syntheticSignoff(),
                items.stream()
                        .map(i -> new FlagReportItemInput(i.flagItemCode(), i.conclusion()))
                        .toList(),
                report.createdAt() == null ? null : report.createdAt().toString());
    }
}
