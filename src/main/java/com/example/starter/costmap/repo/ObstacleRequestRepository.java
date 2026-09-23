package com.example.starter.costmap.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 障碍物更新幂等记录的 H2 存取，主键 (map_id, request_id)。
 */
@Repository
public class ObstacleRequestRepository {

    /**
     * 幂等记录。
     *
     * @param requestHash    请求参数规范化后的 SHA-256 摘要（十六进制）
     * @param appliedVersion 首次应用产生的地图版本号
     */
    public record Record(String requestHash, long appliedVersion) {
    }

    private final JdbcTemplate jdbc;

    public ObstacleRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询指定请求的幂等记录。
     */
    public Optional<Record> find(String mapId, String requestId) {
        return jdbc.query("""
                        SELECT request_hash, applied_version
                        FROM obstacle_request WHERE map_id = ? AND request_id = ?
                        """,
                (rs, rowNum) -> new Record(rs.getString("request_hash"), rs.getLong("applied_version")),
                mapId, requestId).stream().findFirst();
    }

    /**
     * 写入幂等记录；主键冲突时抛出 {@link org.springframework.dao.DuplicateKeyException}。
     */
    public void insert(String mapId, String requestId, String requestHash,
                       long appliedVersion, Instant createdAt) {
        jdbc.update("""
                        INSERT INTO obstacle_request (map_id, request_id, request_hash, applied_version, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                mapId, requestId, requestHash, appliedVersion, Timestamp.from(createdAt));
    }
}
