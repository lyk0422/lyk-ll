package com.example.starter.api;

import java.math.BigDecimal;

/**
 * 水下记录提交结果。
 *
 * @param effective 本条提交后记录是否成对生效（false=等待搭档，单人记录不生效）
 * @param pairState PENDING/EFFECTIVE/FROZEN
 * @param ledgerSeconds24h 配对生效后该潜水员 24h 滚动窗口累计水下秒数（恰 21600 合法）
 */
public record SegmentResponse(
        String operationNo,
        String requestId,
        long segmentId,
        String diverId,
        String phase,
        int planVersion,
        boolean effective,
        String pairState,
        BigDecimal rateMPerMin,
        boolean rateAlarm,
        long ledgerSeconds24h) {
}
