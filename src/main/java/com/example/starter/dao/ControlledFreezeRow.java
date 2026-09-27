package com.example.starter.dao;

import java.time.Instant;

/**
 * 受控方案对每名组员冻结的来源快照（虚构积分模型）。
 *
 * @param sourceDiveRequestId 冻结时最近受控潜次 requestId；首次为空
 * @param sourceVersion       冻结时最近潜次方案版本；首次为 0
 * @param sourceSurfacedAt    冻结时最近已确认出水时刻；首次为空
 * @param pointsAtFreeze      冻结时按计划下潜时刻复算的当前积分
 * @param plannedDescentAt    方案声明的精确计划下潜时刻
 * @param pointsAtStart       实际下潜重查通过后的下潜前积分；未下潜为空
 * @param started             是否已实际下潜
 */
public record ControlledFreezeRow(
        Long id,
        String diveRequestId,
        int version,
        String diverCode,
        String sourceDiveRequestId,
        int sourceVersion,
        Instant sourceSurfacedAt,
        int pointsAtFreeze,
        Instant plannedDescentAt,
        Integer pointsAtStart,
        boolean started,
        Instant createdAtUtc) {
}
