package com.example.starter.costmap.repo;

import com.example.starter.costmap.model.ObstacleRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 障碍物更新审计记录的 H2 存取。
 */
@Repository
public class ObstacleUpdateRepository {

    private final JdbcTemplate jdbc;

    public ObstacleUpdateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条已应用的障碍物更新审计记录。
     */
    public void insert(String mapId, ObstacleRecord record, long appliedVersion, Instant createdAt) {
        jdbc.update("""
                        INSERT INTO obstacle_update
                            (map_id, obstacle_id, request_id, source_id, priority, obstacle_key,
                             x, y, width, height, cells, client_ts, ttl_millis, expires_at,
                             applied_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                mapId,
                record.obstacleId(),
                record.requestId(),
                record.sourceId(),
                record.priority(),
                record.obstacleKey(),
                record.region().x(),
                record.region().y(),
                record.region().width(),
                record.region().height(),
                record.cells(),
                record.clientTs(),
                record.expiresAt() == null ? null : record.expiresAt() - createdAt.toEpochMilli(),
                record.expiresAt(),
                appliedVersion,
                Timestamp.from(createdAt));
    }

    /**
     * 统计指定地图的审计记录数（用于测试校验）。
     */
    public long count(String mapId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM obstacle_update WHERE map_id = ?", Long.class, mapId);
        return count == null ? 0 : count;
    }
}
