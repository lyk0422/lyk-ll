package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 例行巡检开始结果。
 *
 * @param planId      计划ID
 * @param status      计划状态（IN_PROGRESS）
 * @param version     开始后的计划版本号
 * @param startedAt   实际开始时刻（UTC）
 */
public record PlanStartResponse(
        long planId,
        String status,
        int version,
        Instant startedAt) {
}
