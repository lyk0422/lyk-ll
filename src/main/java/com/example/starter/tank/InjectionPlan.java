package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受控加注计划。
 *
 * @param id         主键
 * @param stationId  门站ID
 * @param tankId     储罐ID
 * @param taskId     关联压力联动任务ID，独立计划为 null
 * @param requestId  创建请求的幂等键
 * @param plannedKg  预占量 kg
 * @param actualKg   回执实际消耗 kg，未回执为 null
 * @param status     RESERVED/EXECUTED/CANCELLED/BLOCKED
 * @param plannedAt  计划时刻（业务时间）
 */
public record InjectionPlan(
        Long id,
        Long stationId,
        Long tankId,
        Long taskId,
        String requestId,
        BigDecimal plannedKg,
        BigDecimal actualKg,
        String status,
        LocalDateTime plannedAt) {
}
