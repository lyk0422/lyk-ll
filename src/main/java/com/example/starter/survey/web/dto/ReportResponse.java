package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.InspectionReport;
import com.example.starter.survey.domain.InspectionReportItem;
import com.example.starter.survey.domain.ItemConclusion;
import com.example.starter.survey.domain.ReportStatus;
import com.example.starter.survey.domain.SurveyItem;

import java.util.List;

/** 检查报告修订视图（含逐项结论）。 */
public record ReportResponse(
        Long id,
        String reportNo,
        String shipImo,
        String flagCode,
        String completedAt,
        String attestation,
        int revision,
        int version,
        ReportStatus status,
        List<ItemView> items,
        String createdAt
) {

    /** 逐项结论视图。 */
    public record ItemView(SurveyItem itemCode, ItemConclusion conclusion) {
    }

    /** 将领域记录与逐项结论转换为视图。 */
    public static ReportResponse from(InspectionReport report, List<InspectionReportItem> items) {
        return new ReportResponse(
                report.id(), report.reportNo(), report.shipImo(), report.flagCode(),
                report.completedAt().toString(), report.attestation(),
                report.revision(), report.version(), report.status(),
                items.stream().map(i -> new ItemView(i.itemCode(), i.conclusion())).toList(),
                report.createdAt().toString());
    }
}
