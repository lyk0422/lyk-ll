package com.example.starter.domain;

import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 电池容量读数：按“基站＋抄表时刻”去重，乱序上报按抄表时刻归位。
 *
 * @param id               主键
 * @param stationId        所属基站 ID
 * @param readAt           抄表时刻，UTC
 * @param remainingWh      剩余电池容量，单位 Wh
 * @param estimatedMinutes 按当前放电功率估算的可支撑分钟数
 * @param createdAt        入库时刻，UTC
 */
public record CapacityReading(
        Long id,
        Long stationId,
        Instant readAt,
        BigDecimal remainingWh,
        int estimatedMinutes,
        Instant createdAt
) {

    public static RowMapper<CapacityReading> rowMapper() {
        return (ResultSet rs, int rowNum) -> new CapacityReading(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getTimestamp("read_at").toInstant(),
                rs.getBigDecimal("remaining_wh"),
                rs.getInt("estimated_minutes"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
