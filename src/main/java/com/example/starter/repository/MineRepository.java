package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.Mine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 矿区数据访问。
 */
@Repository
public class MineRepository {

    private final JdbcTemplate jdbc;

    public MineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询矿区。
     */
    public Optional<Mine> findById(long id) {
        return jdbc.query("SELECT * FROM mine WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    /**
     * 冻结矿区并记录时刻与原因。
     */
    public void freeze(long mineId, Instant frozenAt, String reason) {
        jdbc.update("UPDATE mine SET frozen = TRUE, frozen_at = ?, freeze_reason = ? WHERE id = ?",
                Timestamp.from(frozenAt), reason, mineId);
    }

    /**
     * 解除冻结。
     */
    public void unfreeze(long mineId) {
        jdbc.update("UPDATE mine SET frozen = FALSE, frozen_at = NULL, freeze_reason = NULL WHERE id = ?",
                mineId);
    }

    private Mine map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp frozenAt = rs.getTimestamp("frozen_at");
        return new Mine(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getBigDecimal("velocity_threshold"),
                rs.getBoolean("frozen"),
                frozenAt == null ? null : frozenAt.toInstant(),
                rs.getString("freeze_reason"));
    }
}
