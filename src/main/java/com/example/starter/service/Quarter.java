package com.example.starter.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.starter.error.ApiException;

/**
 * 账单季度（格式 yyyyQn，UTC 日界）解析与区间计算。
 */
public final class Quarter {

    private static final Pattern PATTERN = Pattern.compile("^(\\d{4})Q([1-4])$");

    private Quarter() {
    }

    /** 季度区间 [start, end)，按 UTC 日界划分。 */
    public record Range(OffsetDateTime start, OffsetDateTime end) {
    }

    public static Range rangeOf(String quarterLabel) {
        Matcher m = PATTERN.matcher(quarterLabel == null ? "" : quarterLabel);
        if (!m.matches()) {
            throw ApiException.badRequest("INVALID_QUARTER",
                    "季度格式应为 yyyyQn（n 为 1-4），实际: " + quarterLabel);
        }
        int year = Integer.parseInt(m.group(1));
        int quarter = Integer.parseInt(m.group(2));
        LocalDate startDate = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        LocalDate endDate = startDate.plusMonths(3);
        return new Range(startDate.atStartOfDay().atOffset(ZoneOffset.UTC),
                endDate.atStartOfDay().atOffset(ZoneOffset.UTC));
    }
}
