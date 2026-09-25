package com.example.starter.rebate.repo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * rate_library 行。
 */
public record RateLibraryRow(
        Long id,
        String hsCode,
        LocalDate effectiveDate,
        BigDecimal rate
) {
}
