package com.example.starter.water.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据访问层行记录类型。时间字段均为 UTC 的 LocalDateTime（数据库 TIMESTAMP 不含时区）。
 */
public final class Rows {

    private Rows() {
    }

    /** 取水户行。 */
    public record UserRow(long id, String userCode, String userName, String reachCode,
                          BigDecimal annualQuota) {
    }

    /** 计量点行。 */
    public record MeterPointRow(long id, String pointCode, long userId) {
    }

    /** 河段限取状态行。 */
    public record RestrictionRow(long id, String reachCode, boolean active,
                                 LocalDateTime effectiveAt) {
    }

    /** 年度额度台账行。 */
    public record LedgerRow(long id, long userId, int year, BigDecimal baseQuota,
                            BigDecimal carryoverDeduction) {
    }

    /** 月度申报确认版本行。 */
    public record DeclarationRow(long id, long userId, String month, int version,
                                 BigDecimal plannedAmount, BigDecimal acceptedAmount,
                                 boolean restricted, String requestId, LocalDateTime createdAt) {
    }

    /** 申报幂等记录行。 */
    public record IdempotencyRow(String requestId, long userId, String month,
                                 BigDecimal plannedAmount, String status,
                                 BigDecimal acceptedAmount, Long declarationId,
                                 String reason, LocalDateTime createdAt) {
    }

    /** 计量读数行。 */
    public record ReadingRow(long id, String meterPoint, long userId, LocalDateTime readAt,
                             String bucketMonth, BigDecimal cumulativeReading,
                             LocalDateTime createdAt) {
    }

    /** 年度计划封存行。 */
    public record SealRow(long id, long userId, int year, LocalDateTime sealedAt) {
    }
}
