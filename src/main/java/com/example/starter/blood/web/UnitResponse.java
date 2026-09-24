package com.example.starter.blood.web;

import java.time.Instant;

import com.example.starter.blood.domain.BloodUnit;
import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.UnitStatus;

/**
 * 成分血单位视图。
 *
 * @param barcode        唯一条码
 * @param componentType  成分类型
 * @param status         当前状态
 * @param initialResult  初检结果，null 表示未回传
 * @param recheckResult  复检结果，null 表示未回传
 * @param expireAt       可放行截止时刻（UTC，含边界）
 */
public record UnitResponse(
        String barcode,
        String componentType,
        UnitStatus status,
        TestOutcome initialResult,
        TestOutcome recheckResult,
        Instant expireAt) {

    /**
     * 由领域记录构造视图。
     *
     * @param unit 单位记录
     * @return 视图
     */
    public static UnitResponse from(BloodUnit unit) {
        return new UnitResponse(unit.barcode(), unit.componentType(), unit.status(), unit.initialResult(),
                unit.recheckResult(), unit.expireAt());
    }
}
