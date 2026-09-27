package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待补液事件：预占后可用量低于安全储备时生成；补液入账后关闭可满足的事件，
 * 仅关闭不重发旧加注命令。
 *
 * @param id               事件ID
 * @param tankId           储罐ID
 * @param source           触发来源：PLAN 加注计划 / PRESSURE 压力联动
 * @param planId           触发计划ID，压力联动触发为 null
 * @param requiredQtyKg    触发时所需量 kg
 * @param deficitQtyKg     触发时缺口 kg
 * @param status           OPEN 待补液 / CLOSED 已可满足
 * @param createdAt        生成时刻
 * @param closedAt         关闭时刻，未关闭为 null
 * @param closedByRefillId 关闭该事件的补液单ID，未关闭为 null
 */
public record TankRefillEvent(Long id, Long tankId, String source, Long planId,
                              BigDecimal requiredQtyKg, BigDecimal deficitQtyKg, String status,
                              LocalDateTime createdAt, LocalDateTime closedAt,
                              Long closedByRefillId) {
}
