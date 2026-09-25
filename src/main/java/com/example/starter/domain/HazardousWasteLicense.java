package com.example.starter.domain;

import java.time.LocalDate;

/**
 * 危废经营许可（合成库）。valid_to 含当日有效，早于当前日期视为过期。
 */
public record HazardousWasteLicense(
        Long id,
        String licenseNo,
        String holder,
        LocalDate validFrom,
        LocalDate validTo) {
}
