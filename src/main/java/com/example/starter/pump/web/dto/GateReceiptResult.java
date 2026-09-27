package com.example.starter.pump.web.dto;

import java.math.BigDecimal;

/**
 * 闸门回执受理结果。
 *
 * @param receiptId   回执ID
 * @param candidateId 所属切换候选ID
 * @param stationCode 泵站代码
 * @param opening     到位开度（百分比）
 * @param replayed    是否为同键重放返回
 */
public record GateReceiptResult(
        Long receiptId,
        Long candidateId,
        String stationCode,
        BigDecimal opening,
        boolean replayed
) {
}
