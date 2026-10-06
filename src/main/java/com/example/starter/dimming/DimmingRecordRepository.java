package com.example.starter.dimming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 调光留痕查询（日结复算用）与检测采样数据访问。
 */
@Repository
public class DimmingRecordRepository {

    /** 留痕行 */
    public record RecordRow(
            long id,
            String circuitCode,
            int fromLevel,
            int toLevel,
            String source,
            LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public DimmingRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RecordRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RecordRow(
                rs.getLong("id"),
                rs.getString("circuit_code"),
                rs.getInt("from_level"),
                rs.getInt("to_level"),
                rs.getString("source"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    /** 指定日 [start, end) 内的留痕，按时间升序 */
    public List<RecordRow> findBetween(String circuitCode, LocalDateTime start, LocalDateTime end) {
        return jdbc.query(
                "SELECT * FROM dimming_record WHERE circuit_code = ? AND created_at >= ? AND created_at < ?"
                        + " ORDER BY created_at, id",
                DimmingRecordRepository::map, circuitCode, start, end);
    }

    /** 指定时刻之前最近一次调光后的档位，无记录时返回 null（用回路初始档位） */
    public Integer findLevelAt(String circuitCode, LocalDateTime at) {
        List<Integer> levels = jdbc.query(
                "SELECT to_level FROM dimming_record WHERE circuit_code = ? AND created_at < ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, rowNum) -> rs.getInt(1), circuitCode, at);
        return levels.isEmpty() ? null : levels.get(0);
    }

    public int countByCircuit(String circuitCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dimming_record WHERE circuit_code = ?", Integer.class, circuitCode);
        return count == null ? 0 : count;
    }
}
