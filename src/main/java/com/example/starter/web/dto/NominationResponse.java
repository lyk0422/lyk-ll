package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日指定气量响应。
 */
public record NominationResponse(
        String stationCode,
        LocalDate gasDay,
        BigDecimal nominatedEnergyMj,
        BigDecimal penaltyUnitPrice) {
}
