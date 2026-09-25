package com.example.starter.damage.repo.po;

import java.math.BigDecimal;

/** estimate_version 行记录：估价版本。 */
public record EstimateVersionPo(
        Long id,
        Long caseId,
        Integer versionNo,
        String requestId,
        BigDecimal subtotalOwner,
        BigDecimal subtotalTerminal,
        BigDecimal totalAmount,
        BigDecimal maxItemAmount,
        BigDecimal ratio,
        String decision,
        Long createdAtUtc) {
}
