package com.example.starter.dimming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 照明回路数据访问。
 */
@Repository
public class CircuitRepository {

    /** 回路实体快照 */
    public record CircuitRow(
            long id,
            String code,
            String name,
            int currentLevel,
            int initialLevel,
            boolean locked,
            LocalDateTime lastAdjustedAt,
            LocalDateTime transitionUntil) {
    }

    private final JdbcTemplate jdbc;

    public CircuitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static CircuitRow map(ResultSet rs, int rowNum) throws SQLException {
        return new CircuitRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("current_level"),
                rs.getInt("initial_level"),
                rs.getBoolean("locked"),
                rs.getObject("last_adjusted_at", LocalDateTime.class),
                rs.getObject("transition_until", LocalDateTime.class));
    }

    public Optional<CircuitRow> findByCode(String code) {
        List<CircuitRow> rows = jdbc.query(
                "SELECT * FROM circuit WHERE code = ?", CircuitRepository::map, code);
        return rows.stream().findFirst();
    }

    /** 悲观行锁，串行化同一回路的并发调光 */
    public Optional<CircuitRow> findByCodeForUpdate(String code) {
        List<CircuitRow> rows = jdbc.query(
                "SELECT * FROM circuit WHERE code = ? FOR UPDATE", CircuitRepository::map, code);
        return rows.stream().findFirst();
    }

    public List<CircuitRow> findAll() {
        return jdbc.query("SELECT * FROM circuit ORDER BY code", CircuitRepository::map);
    }

    public void updateLevel(String code, int level, LocalDateTime adjustedAt, LocalDateTime transitionUntil) {
        jdbc.update(
                "UPDATE circuit SET current_level = ?, last_adjusted_at = ?, transition_until = ? WHERE code = ?",
                level, adjustedAt, transitionUntil, code);
    }

    public void updateLocked(String code, boolean locked) {
        jdbc.update("UPDATE circuit SET locked = ? WHERE code = ?", locked, code);
    }
}
