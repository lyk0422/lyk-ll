package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 原始样本视图。体积均为微升字符串（三位小数以内）。
 */
public record SpecimenView(
        String specimenId,
        String volumeUl,
        String remainingUl,
        String status,
        String custodian,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
