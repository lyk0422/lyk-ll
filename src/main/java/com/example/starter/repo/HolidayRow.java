package com.example.starter.repo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 合成节假日日历行。peakFactorBp 为峰值系数基点：节前 3 日预测取现=日均×bp/100，150 即 1.5 倍。
 */
public record HolidayRow(
        long id,
        LocalDate holidayDate,
        String name,
        int peakFactorBp,
        Instant createdAt
) {
}
