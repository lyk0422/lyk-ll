package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 分装管视图。体积均为微升字符串。
 */
public record AliquotView(
        String aliquotId,
        String specimenId,
        String volumeUl,
        String consumedUl,
        String reservedUl,
        String availableUl,
        String status,
        String custodian,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
