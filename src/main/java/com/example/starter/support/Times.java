package com.example.starter.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 业务时间一律使用 UTC；H2 DATETIME 列不带时区，统一在边界换算。
 */
public final class Times {

    private Times() {
    }

    public static LocalDateTime toSql(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromSql(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
