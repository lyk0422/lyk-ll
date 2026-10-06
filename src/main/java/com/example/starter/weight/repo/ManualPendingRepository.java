package com.example.starter.weight.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.ManualPendingRow;

/**
 * 证据链缺项人工待补仓储：待补单随非现场记录同事务生成。
 */
@Repository
public class ManualPendingRepository {

    private static final RowMapper<ManualPendingRow> MAPPER = (rs, n) -> new ManualPendingRow(
            rs.getLong("id"),
            rs.getLong("offsite_weigh_id"),
            rs.getString("plate_no"),
            rs.getString("lane_no"),
            rs.getTimestamp("weigh_time").toInstant(),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ManualPendingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按非现场称重记录查询待补单；不存在返回 null。
     */
    public ManualPendingRow findByOffsiteWeighId(long offsiteWeighId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM manual_pending WHERE offsite_weigh_id = ?", MAPPER, offsiteWeighId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 插入人工待补单。
     */
    public void insert(long offsiteWeighId, String plateNo, String laneNo, Instant weighTime,
                       String reason, Instant now) {
        jdbc.update("""
                INSERT INTO manual_pending
                  (offsite_weigh_id, plate_no, lane_no, weigh_time, reason, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                """, offsiteWeighId, plateNo, laneNo, Timestamp.from(weighTime), reason,
                Timestamp.from(now));
    }

    /**
     * 统计待补单数（测试核查用）。
     */
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM manual_pending", Integer.class);
        return count == null ? 0 : count;
    }
}
