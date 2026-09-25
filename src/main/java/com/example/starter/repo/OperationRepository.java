package com.example.starter.repo;

import com.example.starter.domain.OperationRow;
import com.example.starter.domain.OperationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 列车作业主表数据访问。
 */
@Repository
public class OperationRepository {

    private static final RowMapper<OperationRow> ROW_MAPPER = (rs, i) -> new OperationRow(
            rs.getLong("id"),
            rs.getString("track_no"),
            rs.getString("train_no"),
            rs.getInt("set_pressure_kpa"),
            OperationStatus.valueOf(rs.getString("status")),
            rs.getInt("consist_version"),
            rs.getBoolean("recovery_pending"),
            rs.getBoolean("recovery_ok"));

    private final JdbcTemplate jdbcTemplate;

    public OperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 接入新作业; active_track 唯一索引冲突时抛出 DuplicateKeyException(同股道已有作业中列车)。
     */
    public long insert(String trackNo, String trainNo, int setPressureKpa) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO train_operation (track_no, train_no, set_pressure_kpa, status,"
                            + " consist_version, recovery_pending, recovery_ok, active_track)"
                            + " VALUES (?, ?, ?, 'RECEIVED', 1, FALSE, FALSE, ?)",
                    new String[]{"id"});
            ps.setString(1, trackNo);
            ps.setString(2, trainNo);
            ps.setInt(3, setPressureKpa);
            ps.setString(4, trackNo);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<OperationRow> findById(long id) {
        return jdbcTemplate.query(
                        "SELECT id, track_no, train_no, set_pressure_kpa, status, consist_version,"
                                + " recovery_pending, recovery_ok FROM train_operation WHERE id = ?",
                        ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 摘挂后递增编组版本, 并在同一事务内复位风压恢复标记。
     */
    public void bumpConsistVersion(long id, int newVersion) {
        jdbcTemplate.update(
                "UPDATE train_operation SET consist_version = ?, recovery_pending = FALSE,"
                        + " recovery_ok = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                newVersion, id);
    }

    /**
     * 感度合格后标记等待风压恢复定压。
     */
    public void markRecoveryPending(long id) {
        jdbcTemplate.update(
                "UPDATE train_operation SET recovery_pending = TRUE, recovery_ok = FALSE,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    /**
     * 风压恢复至定压(恰达定压视为恢复)。
     */
    public void markRecovered(long id) {
        jdbcTemplate.update(
                "UPDATE train_operation SET recovery_pending = FALSE, recovery_ok = TRUE,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    /**
     * 发车确认。
     */
    public void confirmDeparture(long id) {
        jdbcTemplate.update(
                "UPDATE train_operation SET status = 'DEPARTURE_CONFIRMED',"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    /**
     * 销记(终态): 释放股道占用。
     */
    public void close(long id) {
        jdbcTemplate.update(
                "UPDATE train_operation SET status = 'CLOSED', active_track = NULL,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    /**
     * 统计股道上作业中列车数(供并发断言)。
     */
    public int countActiveByTrack(String trackNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM train_operation WHERE active_track = ?", Integer.class, trackNo);
        return count == null ? 0 : count;
    }
}
