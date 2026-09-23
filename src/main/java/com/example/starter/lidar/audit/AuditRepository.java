package com.example.starter.lidar.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 隔离/损坏审计仓库：记录每个损坏块的隔离事件。
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 建表（幂等）。 */
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lidar_audit (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
                    event_type  VARCHAR(32)  NOT NULL COMMENT '事件类型 QUARANTINED/REBUILD_BAD',
                    chunk_id    VARCHAR(255) COMMENT '块 ID；无法从头解析时为空',
                    file_name   VARCHAR(512) NOT NULL COMMENT '涉及文件名',
                    reason      VARCHAR(1024) COMMENT '损坏/隔离原因',
                    created_at  TIMESTAMP    NOT NULL COMMENT '事件时间 epoch 毫秒'
                )
                """);
    }

    /** 记录一条审计事件，返回事件 ID。 */
    public long record(String eventType, String chunkId, String fileName, String reason, Instant at) {
        jdbc.update("""
                        INSERT INTO lidar_audit (event_type, chunk_id, file_name, reason, created_at)
                        VALUES (?,?,?,?,?)
                        """,
                eventType, chunkId, fileName,
                reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000)),
                Timestamp.from(at));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    /** 按事件类型统计。 */
    public long countByType(String eventType) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lidar_audit WHERE event_type = ?", Long.class, eventType);
        return c == null ? 0 : c;
    }
}
