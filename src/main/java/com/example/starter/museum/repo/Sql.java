package com.example.starter.museum.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * SQL 绑定工具：Instant 统一按 UTC 墙钟时间写入 H2 TIMESTAMP，
 * 与 {@link RowMappers#instant} 的读取方式对称，保证跨 JVM 时区往返一致。
 */
final class Sql {

    private Sql() {
    }

    static Timestamp ts(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
