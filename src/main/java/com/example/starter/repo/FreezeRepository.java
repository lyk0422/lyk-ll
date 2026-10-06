package com.example.starter.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 高风险路段冻结数据访问。segment_id 唯一约束保证同一路段同一时刻仅一条冻结。
 */
@Repository
public class FreezeRepository {

    private final JdbcTemplate jdbc;

    public FreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long segmentId, long warningId, LocalDateTime frozenAt) {
        jdbc.update("INSERT INTO segment_freeze(segment_id, warning_id, frozen_at) VALUES (?, ?, ?)",
                segmentId, warningId, frozenAt);
    }

    public int deleteByWarningId(long warningId) {
        return jdbc.update("DELETE FROM segment_freeze WHERE warning_id = ?", warningId);
    }

    public boolean existsBySegmentId(long segmentId) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_freeze WHERE segment_id = ?", Long.class, segmentId);
        return cnt != null && cnt > 0;
    }

    public List<Long> findSegmentIds() {
        return jdbc.queryForList("SELECT segment_id FROM segment_freeze ORDER BY segment_id",
                Long.class);
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM segment_freeze", Long.class);
    }
}
