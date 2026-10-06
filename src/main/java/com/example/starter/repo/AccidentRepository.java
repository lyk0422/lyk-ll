package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 事故锁定/解除留痕数据访问。锁定与解除与其业务变更在同一事务内写入。
 */
@Repository
public class AccidentRepository {

    /** 事故事件留痕。 */
    public record AccidentEvent(long id, long circuitId, String action, Integer level,
                                String operator, String requestId, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public AccidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入事故事件（LOCK/UNLOCK）。 */
    public void insert(long circuitId, String action, Integer level, String operator, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO accident_event (circuit_id, action, level, operator, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                circuitId, action, level, operator, requestId, Timestamp.from(createdAt));
    }

    /** 查询回路全部事故事件，按时间升序。 */
    public List<AccidentEvent> findByCircuit(long circuitId) {
        return jdbc.query("SELECT id, circuit_id, action, level, operator, request_id, created_at"
                        + " FROM accident_event WHERE circuit_id = ? ORDER BY created_at, id",
                this::map, circuitId);
    }

    private AccidentEvent map(ResultSet rs, int rowNum) throws SQLException {
        int level = rs.getInt("level");
        boolean levelNull = rs.wasNull();
        return new AccidentEvent(
                rs.getLong("id"),
                rs.getLong("circuit_id"),
                rs.getString("action"),
                levelNull ? null : level,
                rs.getString("operator"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
