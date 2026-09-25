package com.example.starter.reinsurance.common;

import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 账单季度（UTC 日界）工具。季度标签格式 {@code yyyy-Qn}。
 */
public final class Periods {

    private static final Pattern PERIOD = Pattern.compile("(\\d{4})-Q([1-4])");

    private Periods() {
    }

    /** 校验并规范化季度标签。 */
    public static String requireValid(String period) {
        if (period == null || !PERIOD.matcher(period).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "季度格式必须为 yyyy-Qn（如 2026-Q3）: " + period);
        }
        return period;
    }

    /** 季度起始日期（UTC 日界，含）。 */
    public static LocalDate startDate(String period) {
        Matcher matcher = PERIOD.matcher(requireValid(period));
        matcher.matches();
        int year = Integer.parseInt(matcher.group(1));
        int quarter = Integer.parseInt(matcher.group(2));
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }

    /** 季度结束日期（UTC 日界，不含）。 */
    public static LocalDate endDateExclusive(String period) {
        return startDate(period).plusMonths(3);
    }

    /** 解析 {@code yyyy-MM-dd} 日期，失败返回 400。 */
    public static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " 日期格式必须为 yyyy-MM-dd: " + value);
        }
    }
}
