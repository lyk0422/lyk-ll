package com.example.starter.waterlogging.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泵站启停指令 pump_command 映射。
 */
public record PumpCommand(
        Long id,
        Long planId,
        String stationCode,
        String action,
        Integer sequenceNo,
        LocalDateTime commandTime,
        BigDecimal gateOpening,
        Boolean gateAdjusted,
        String requestId,
        LocalDateTime createdAt) {
}
