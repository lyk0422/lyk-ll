package com.example.starter.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日指定气量持久化对象。
 */
public record NominationPO(
        Long id,
        String stationCode,
        LocalDate gasDay,
        BigDecimal nominatedEnergyMj,
        BigDecimal penaltyUnitPrice) {
}
