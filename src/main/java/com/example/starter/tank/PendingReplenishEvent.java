package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待补液事件：预占将使可用量低于安全储备时生成，加注不下发。
 *
 * @param id              主键
 * @param stationId       门站ID
 * @param tankId          储罐ID
 * @param sourceRequestId 触发事件的加注计划请求号（仅记录，不自动重发）
 * @param source          PRESSURE / MANUAL
 * @param requiredKg      被拦截的预占所需量 kg
 * @param status          OPEN 待补液 / CLOSED 已被可满足的补液关闭
 * @param closedOrderId   关闭事件的补液单ID
 * @param createdAt       生成时刻
 * @param closedAt        关闭时刻
 */
public record PendingReplenishEvent(
        Long id,
        Long stationId,
        Long tankId,
        String sourceRequestId,
        String source,
        BigDecimal requiredKg,
        String status,
        Long closedOrderId,
        LocalDateTime createdAt,
        LocalDateTime closedAt) {
}
