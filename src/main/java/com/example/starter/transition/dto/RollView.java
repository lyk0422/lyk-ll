package com.example.starter.transition.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 过渡纸卷视图，含改判链事件。
 */
public record RollView(
        String rollNo,
        String orderNo,
        String paperMachine,
        BigDecimal lengthM,
        String verdict,
        String warehouseStatus,
        boolean eligibleReexam,
        int reexamCount,
        LocalDateTime registeredAt,
        LocalDateTime verdictChangedAt,
        List<VerdictEventView> chain) {

    /**
     * 改判链单条事件。
     */
    public record VerdictEventView(
            String eventType,
            String fromVerdict,
            String toVerdict,
            String requestId,
            LocalDateTime eventTime) {
    }
}
