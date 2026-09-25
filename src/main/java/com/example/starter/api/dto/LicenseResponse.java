package com.example.starter.api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合成危废经营许可视图。
 */
public record LicenseResponse(
        String licenseNo,
        String holderName,
        LocalDate validFrom,
        LocalDate validTo,
        boolean revoked,
        LocalDateTime createdAt) {
}
