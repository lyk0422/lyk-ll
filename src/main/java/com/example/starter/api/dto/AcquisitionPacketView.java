package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 原始采集包证据视图。
 *
 * @param status BUFFERED=已缓冲待补缺口 / COMMITTED=已入账 / LATE=归档后迟到仅记录
 */
public record AcquisitionPacketView(
        int seq,
        String pointCode,
        BigDecimal torque,
        Instant measuredAt,
        String status,
        String contentHash,
        String batchNo) {
}
