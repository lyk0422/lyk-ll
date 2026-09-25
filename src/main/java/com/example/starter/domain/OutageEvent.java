package com.example.starter.domain;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 停电事件：同一基站按“基站＋停电起始时刻”去重。
 *
 * @param id            主键
 * @param stationId     所属基站 ID
 * @param outageStartAt 停电起始时刻，UTC
 * @param status        ACTIVE 停电中 / RESTORED 已恢复
 * @param restoredAt    市电恢复时刻，UTC；停电中为 null
 * @param createdAt     创建时刻，UTC
 */
public record OutageEvent(
        Long id,
        Long stationId,
        Instant outageStartAt,
        OutageStatus status,
        Instant restoredAt,
        Instant createdAt
) {

    public static RowMapper<OutageEvent> rowMapper() {
        return (ResultSet rs, int rowNum) -> {
            Timestamp restored = rs.getTimestamp("restored_at");
            return new OutageEvent(
                    rs.getLong("id"),
                    rs.getLong("station_id"),
                    rs.getTimestamp("outage_start_at").toInstant(),
                    OutageStatus.valueOf(rs.getString("status")),
                    restored == null ? null : restored.toInstant(),
                    rs.getTimestamp("created_at").toInstant()
            );
        };
    }
}
