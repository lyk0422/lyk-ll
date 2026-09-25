package com.example.starter.rt.dto;

import java.time.OffsetDateTime;

/**
 * 分次台账行。
 *
 * @param fractionId       台账ID
 * @param planVersionId    执行时版本ID
 * @param versionNo        执行时版本号
 * @param fractionNo       分次号
 * @param deliveredDoseCgy 实际投递剂量(cGy)
 * @param requestId        幂等键
 * @param executedAt       执行时间
 */
public record FractionLedgerItem(
        Long fractionId,
        Long planVersionId,
        Integer versionNo,
        Integer fractionNo,
        Long deliveredDoseCgy,
        String requestId,
        OffsetDateTime executedAt) {
}
