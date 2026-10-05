package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 投料记录访问。投料受理前由服务层在同一事务内校验升温曲线状态。
 */
@Repository
public class FeedRepository {

    private final JdbcTemplate jdbc;

    public FeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落库投料记录（request_id 唯一约束兜底幂等）。 */
    public void insert(String requestId, String kilnId, String operatorId, LocalDateTime fedAt) {
        jdbc.update("""
                INSERT INTO feed_record (request_id, kiln_id, operator_id, fed_at)
                VALUES (?, ?, ?, ?)
                """, requestId, kilnId, operatorId, Timestamp.valueOf(fedAt));
    }

    public int countByKiln(String kilnId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_record WHERE kiln_id = ?", Integer.class, kilnId);
        return count == null ? 0 : count;
    }
}
