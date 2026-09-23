package com.example.starter.web.dto;

import java.util.List;

/**
 * 体积账本：围绕某原始样本聚合样本、分装管与申请占用。
 */
public record VolumeLedgerResponse(
        SpecimenView specimen,
        List<AliquotView> aliquots,
        List<ReservationView> reservations) {
}
