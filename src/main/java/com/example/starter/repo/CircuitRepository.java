package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.Circuit;
import com.example.starter.domain.CircuitStatus;

/**
 * 照明回路数据访问。
 */
@Repository
public class CircuitRepository {

    private final JdbcTemplate jdbc;

    public CircuitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按主键查询回路。 */
    public Optional<Circuit> findById(long id) {
        List<Circuit> rows = jdbc.query(baseSelect() + " WHERE id = ?", this::map, id);
        return rows.stream().findFirst();
    }

    /** 按主键查询并加行级排他锁，串行化同一回路的并发调光。 */
    public Optional<Circuit> findByIdForUpdate(long id) {
        List<Circuit> rows = jdbc.query(baseSelect() + " WHERE id = ? FOR UPDATE", this::map, id);
        return rows.stream().findFirst();
    }

    /** 查询全部回路。 */
    public List<Circuit> findAll() {
        return jdbc.query(baseSelect() + " ORDER BY id", this::map);
    }

    /** 应用调光结果：更新当前档位、最近调光时间与过渡截止时间。 */
    public void applyDim(long id, int newLevel, Instant lastAdjustedAt, Instant transitionUntil) {
        jdbc.update("UPDATE circuit SET current_level = ?, last_adjusted_at = ?, transition_until = ? WHERE id = ?",
                newLevel, Timestamp.from(lastAdjustedAt),
                transitionUntil == null ? null : Timestamp.from(transitionUntil), id);
    }

    /** 事故锁定：进入事故状态并记录锁定档位。 */
    public void applyAccidentLock(long id, int lockedLevel) {
        jdbc.update("UPDATE circuit SET status = 'ACCIDENT', locked_level = ? WHERE id = ?", lockedLevel, id);
    }

    /** 事故解除：恢复正常状态并清空锁定档位。 */
    public void applyAccidentUnlock(long id) {
        jdbc.update("UPDATE circuit SET status = 'NORMAL', locked_level = NULL WHERE id = ?", id);
    }

    private String baseSelect() {
        return "SELECT id, code, name, current_level, initial_level, base_level, max_level,"
                + " brightness_threshold, status, locked_level, last_adjusted_at, transition_until FROM circuit";
    }

    private Circuit map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lockedAt = rs.getTimestamp("last_adjusted_at");
        Timestamp transitionUntil = rs.getTimestamp("transition_until");
        int lockedLevel = rs.getInt("locked_level");
        boolean lockedLevelNull = rs.wasNull();
        return new Circuit(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("current_level"),
                rs.getInt("initial_level"),
                rs.getInt("base_level"),
                rs.getInt("max_level"),
                rs.getInt("brightness_threshold"),
                CircuitStatus.valueOf(rs.getString("status")),
                lockedLevelNull ? null : lockedLevel,
                lockedAt == null ? null : lockedAt.toInstant(),
                transitionUntil == null ? null : transitionUntil.toInstant());
    }
}
