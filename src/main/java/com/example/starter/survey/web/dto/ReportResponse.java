package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.ExternalReport;
import com.example.starter.survey.domain.ExternalReportItem;

import java.util.List;

/** 外部报告修订视图（携带逐项结论）。 */
public record ReportResponse(
        long id,
        String reportNo,
        int revisionNo,
        String shipImo,
        String flagCode,
        String completedAt,
        String signatureMarker,
        String status,
        Long supersededBy,
        List<ItemView> items
) {

    /** 逐项结论视图。 */
    public record ItemView(String itemCode, String result, String note) {
        static ItemView from(ExternalReportItem item) {
            return new ItemView(item.itemCode(), item.result().name(), item.note());
        }
    }

    /** 组装报告修订视图。 */
    public static ReportResponse from(ExternalReport report, List<ExternalReportItem> items) {
        return new ReportResponse(
                report.id(), report.reportNo(), report.revisionNo(), report.shipImo(),
                report.flagCode(),
                report.completedAt() == null ? null : report.completedAt().toString(),
                report.signatureMarker(), report.status().name(), report.supersededBy(),
                items.stream().map(ItemView::from).toList());
    }
}
