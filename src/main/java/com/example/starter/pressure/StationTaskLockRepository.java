package com.example.starter.pressure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 联动任务并发查询：同一门站同一检测时刻是否已有联动任务，
 * 权威裁决由 linkage_task(station_id, detected_at) 唯一约束保证。
 */
@Repository
public class StationTaskLockRepository {

    private final JdbcTemplate jdbc;

    public StationTaskLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 同一门站同一时刻是否已存在联动任务（受理前预检，竞态由唯一索引兜底）。
     */
    public boolean existsAtSameInstant(Long stationId, LocalDateTime detectedAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM linkage_task WHERE station_id = ? AND detected_at = ?",
                Integer.class, stationId, detectedAt);
        return count != null && count > 0;
    }
}
