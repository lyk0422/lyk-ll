package com.example.starter.waterlogging.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单机启停指令结果。
 *
 * @param commandId     启停台账主键
 * @param sequenceNo    在预降单序列中的序号
 * @param gateAdjusted  闸门是否与机组同事务调整到位
 * @param runStatus     执行后机组状态
 */
public record PumpCommandResult(
        Long commandId,
        Long planId,
        String stationCode,
        String action,
        Integer sequenceNo,
        LocalDateTime commandTime,
        BigDecimal gateOpening,
        boolean gateAdjusted,
        String runStatus) {
}
