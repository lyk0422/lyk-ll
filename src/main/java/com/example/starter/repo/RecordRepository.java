package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 调光留痕数据访问。留痕含前后档位与触发来源，是日结能耗复算的依据。
 */
@Repository
public class RecordRepository {

    /** 单条调光留痕。 */
    public record DimRecord(long id, long circuitId, long commandId, int fromLevel, int toLevel,
                            String source, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public RecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入留痕。 */
    public void insert(long circuitId, long commandId, int fromLevel, int toLevel, String source, Instant createdAt) {
        jdbc.update("INSERT INTO dimming_record (circuit_id, command_id, from_level, to_level, source, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                circuitId, commandId, fromLevel, toLevel, source, Timestamp.from(createdAt));
    }

    /** 查询回路在 [start, end) 区间内的留痕，按时间升序。 */
    public List<DimRecord> findBetween(long circuitId, Instant start, Instant end) {
        return jdbc.query("SELECT id, circuit_id, command_id, from_level, to_level, source, created_at"
                        + " FROM dimming_record WHERE circuit_id = ? AND created_at >= ? AND created_at < ?"
                        + " ORDER BY created_at, id",
                this::map, circuitId, Timestamp.from(start), Timestamp.from(end));
    }

    /** 查询某时刻之前最近一次留痕（用于确定日结起点档位）。 */
    public Optional<DimRecord> findLatestBefore(long circuitId, Instant instant) {
        List<DimRecord> rows = jdbc.query("SELECT id, circuit_id, command_id, from_level, to_level, source, created_at"
                        + " FROM dimming_record WHERE circuit_id = ? AND created_at < ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1",
                this::map, circuitId, Timestamp.from(instant));
        return rows.stream().findFirst();
    }

    /** 统计回路留痕数（测试核验用）。 */
    public int countByCircuit(long circuitId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dimming_record WHERE circuit_id = ?", Integer.class, circuitId);
        return count == null ? 0 : count;
    }

    private DimRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new DimRecord(
                rs.getLong("id"),
                rs.getLong("circuit_id"),
                rs.getLong("command_id"),
                rs.getInt("from_level"),
                rs.getInt("to_level"),
                rs.getString("source"),
                rs.getTimestamp("created_at").toInstant());
    }
}
