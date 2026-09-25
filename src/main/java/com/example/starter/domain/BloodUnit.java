package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 成分血单位记录（对应 blood_unit 表）。
 *
 * @param id            主键
 * @param barcode       唯一条码
 * @param status        当前状态
 * @param initialResult 初检结果，NULL 表示未回传
 * @param recheckResult 复检结果，NULL 表示未回传
 * @param expiryDate    效期当日（UTC 日期），当日 23:59:59 UTC 之后禁止放行
 * @param createdAt     入库时刻（UTC）
 * @param updatedAt     最近状态变更时刻（UTC）
 * @param version       乐观锁版本号
 */
public record BloodUnit(
        Long id,
        String barcode,
        UnitStatus status,
        TestResultValue initialResult,
        TestResultValue recheckResult,
        LocalDate expiryDate,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /**
     * 计算效期边界：允许放行的最后时刻为 expiryDate 当日 23:59:59.999... UTC，
     * 即返回次日 00:00:00 UTC（不含）；now 严格小于返回值时仍可放行。
     */
    public Instant releaseDeadlineExclusive() {
        return expiryDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    /**
     * 判定给定 UTC 时刻是否已过期（禁止放行）。恰在 23:59:59 边界时刻仍可放行。
     */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(releaseDeadlineExclusive());
    }
}
