package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagReportRevision;

import java.util.List;

/** 报告修订视图（含逐项结论）。 */
public record FlagReportRevisionResponse(
        Long id,
        Integer revision,
        String completedAt,
        String attestation,
        List<FlagReportItemView> items,
        String createdAt
) {

    public static FlagReportRevisionResponse from(FlagReportRevision revision,
                                                  List<FlagReportItemView> items) {
        return new FlagReportRevisionResponse(
                revision.id(), revision.revision(),
                revision.completedAt() == null ? null : revision.completedAt().toString(),
                revision.attestation(), items,
                revision.createdAt() == null ? null : revision.createdAt().toString());
    }
}
