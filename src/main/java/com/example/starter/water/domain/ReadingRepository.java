package com.example.starter.water.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 计量读数仓储；按“计量点＋表底时刻”唯一去重。
 */
public interface ReadingRepository {

    /**
     * 读数行。
     *
     * @param readingTime 表底时刻（UTC）
     * @param month       按表底时刻归入的 UTC 自然月 yyyy-MM
     * @param cumulative  表底累计读数（m³）
     * @param delta       与按表底时刻排序的前一读数之差（m³）；首个读数增量等于表底值
     */
    record ReadingRow(
            Long id,
            Long meterPointId,
            Instant readingTime,
            String month,
            BigDecimal cumulative,
            BigDecimal delta,
            Instant receivedAt) {
    }

    /**
     * 表底时刻重复时返回已存在读数，不重复累计。
     */
    Optional<ReadingRow> findByMeterAndTime(Long meterPointId, Instant readingTime);

    /**
     * 插入新读数；调用方需先锁定计量点，保证 delta 基于“乱序到达下仍按表底时刻排序”的相邻读数重算。
     */
    ReadingRow insert(Long meterPointId, Instant readingTime, String month, BigDecimal cumulative,
                      BigDecimal delta, Instant receivedAt);

    /**
     * 行级锁定计量点全部读数并按表底时刻升序返回（并发上报串行化、乱序插入后重算增量）。
     */
    List<ReadingRow> lockOrderedReadings(Long meterPointId);

    /**
     * 用重算后的增量更新读数（乱序插入后，后一表底读数的增量需重算）。
     */
    void updateDelta(Long id, BigDecimal delta);

    /**
     * 某取水户指定年度（UTC）按月汇总的实际取水量（m³），键为 yyyy-MM。
     */
    List<MonthTotal> monthlyTotalsOfUserYear(Long userId, int year);

    /**
     * 月合计行。
     */
    record MonthTotal(String month, BigDecimal total) {
    }
}
