package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 受控跑道响应。
 *
 * @param runwayId     跑道编号
 * @param lengthMeters 跑道长度（单位：米）
 * @param version      聚合乐观锁版本
 * @param createdAt    登记时间（UTC）
 * @param updatedAt    最近变更时间（UTC）
 */
public record ControlledRunwayResponse(
        String runwayId,
        int lengthMeters,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
