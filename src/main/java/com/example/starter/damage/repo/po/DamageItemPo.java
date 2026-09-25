package com.example.starter.damage.repo.po;

import java.math.BigDecimal;

/** damage_item 行记录：残损清单明细。 */
public record DamageItemPo(
        Long id,
        Long caseId,
        Long surveyId,
        String code,
        String location,
        String responsibility,
        String repairCode,
        String repairName,
        BigDecimal unitCost,
        Integer quantity,
        BigDecimal amount,
        Long createdAtUtc) {
}
