package com.example.starter.pump.domain;

import java.math.BigDecimal;

/**
 * 候选固定的单站闸门目标。
 *
 * @param id            闸门目标ID
 * @param candidateId   所属切换候选ID
 * @param stationCode   泵站代码
 * @param targetOpening 目标闸门开度（百分比，0-100）
 */
public record GateTarget(
        Long id,
        Long candidateId,
        String stationCode,
        BigDecimal targetOpening
) {
}
