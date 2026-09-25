package com.example.starter.roaming.domain;

import java.time.LocalDateTime;

/**
 * 差异挂账行：封存后迟到的计量快照挂账，人工核销且只能核销一次。
 */
public record DiscrepancyRow(
        Long id,
        String discrepancyNo,
        String sessionNo,
        int meterSeq,
        long meterWh,
        LocalDateTime snapshotAt,
        String sourceRequestId,
        Long batchId,
        String status,
        LocalDateTime writeOffAt,
        String writeOffBy,
        String writeOffRemark,
        String writeOffRequestId,
        LocalDateTime createdAt) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_WRITTEN_OFF = "WRITTEN_OFF";
}
