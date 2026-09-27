package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待补液事件：预占后可用量将低于安全储备时生成，补液入账使可用量回到安全储备之上后关闭。
 *
 * @param id         主键
 * @param stationId  门站ID
 * @param tankId     储罐ID
 * @param planId     触发的加注计划ID，压力联动触发的独立事件为 null
 * @param taskId     触发的压力联动任务ID，独立计划触发为 null
 * @param source     PLAN / PRESSURE
 * @param status     PENDING / CLOSED
 * @param deficitKg  触发时相对安全储备的缺口 kg
 * @param createdAt  生成时刻
 */
public record RefillEvent(
        Long id,
        Long stationId,
        Long tankId,
        Long planId,
        Long taskId,
        String source,
        String status,
        BigDecimal deficitKg,
        LocalDateTime createdAt) {
}
