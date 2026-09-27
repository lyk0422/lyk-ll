package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划：先预占所需量，预占后可用量不得低于安全储备（恰等允许）。
 *
 * @param id            计划ID
 * @param tankId        储罐ID
 * @param requestId     创建计划的幂等键
 * @param plannedAt     计划时刻（业务时间）
 * @param requiredQtyKg 计划预占量 kg
 * @param status        RESERVED/EXECUTED/CANCELLED/BLOCKED
 * @param actualQtyKg   回执实际消耗 kg，不得大于预占量；未执行为 null
 * @param executedAt    执行时刻，未执行为 null
 * @param cancelledAt   取消时刻，未取消为 null
 */
public record TankPlan(Long id, Long tankId, String requestId, LocalDateTime plannedAt,
                       BigDecimal requiredQtyKg, String status, BigDecimal actualQtyKg,
                       LocalDateTime executedAt, LocalDateTime cancelledAt) {
}
