package com.example.starter.blood.domain;

import java.time.Instant;

/**
 * 成分血单位记录（合成数据）。
 *
 * @param id             自增主键
 * @param barcode        唯一条码
 * @param componentType  成分类型（合成数据）
 * @param status         当前状态，见 {@link UnitStatus}
 * @param initialResult  初检结果，null 表示未回传
 * @param recheckResult  复检结果，null 表示未回传
 * @param expireAt       可放行截止时刻（UTC，效期当日 23:59:59，含边界）
 * @param createdAt      入库时刻（UTC）
 * @param updatedAt      最近状态变更时刻（UTC）
 */
public record BloodUnit(
        long id,
        String barcode,
        String componentType,
        UnitStatus status,
        TestOutcome initialResult,
        TestOutcome recheckResult,
        Instant expireAt,
        Instant createdAt,
        Instant updatedAt) {
}
