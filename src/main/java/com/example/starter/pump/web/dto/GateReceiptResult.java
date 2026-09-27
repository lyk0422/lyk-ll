package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 闸门到位回执结果。
 *
 * @param sequenceId  回执归属序列ID
 * @param stationCode 泵站代码
 * @param opening      到位开度（百分比）
 * @param receiptTime  回执UTC时刻
 * @param replayed     是否同键同参重放
 */
public record GateReceiptResult(
        Long sequenceId,
        String stationCode,
        BigDecimal opening,
        OffsetDateTime receiptTime,
        boolean replayed
) {
}
