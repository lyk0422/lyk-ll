package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 闸门合成到位回执：按候选+站点唯一。
 *
 * @param id          回执ID
 * @param candidateId 所属切换候选ID
 * @param stationCode 泵站代码
 * @param opening     到位开度（百分比，0-100）
 * @param receivedAt  回执接收时刻（Asia/Shanghai）
 */
public record GateReceipt(
        Long id,
        Long candidateId,
        String stationCode,
        BigDecimal opening,
        LocalDateTime receivedAt
) {
}
