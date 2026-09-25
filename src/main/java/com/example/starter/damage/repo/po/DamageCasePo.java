package com.example.starter.damage.repo.po;

import java.math.BigDecimal;

/** damage_case 行记录；时间均为 UTC 毫秒，金额单位元。 */
public record DamageCasePo(
        Long id,
        String caseNo,
        String containerNo,
        BigDecimal containerValue,
        String status,
        String activeFlag,
        Long currentEstimateId,
        Long approvedAtUtc,
        Long validUntilUtc,
        Integer repairAttempts,
        Long closedAtUtc,
        String closeOutcome,
        Long createdAtUtc,
        Long updatedAtUtc) {
}
