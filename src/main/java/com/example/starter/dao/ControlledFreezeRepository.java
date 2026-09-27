package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 受控方案冻结快照访问（虚构积分模型）。
 *
 * <p>方案生成/变更时同事务替换两名组员的冻结快照；实际下潜重查通过后回写下潜前积分与占用标记。</p>
 */
@Repository
public class ControlledFreezeRepository {

    private static final RowMapper<ControlledFreezeRow> MAPPER = (rs, n) -> new ControlledFreezeRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getInt("version"),
            rs.getString("diver_code"),
            rs.getString("source_dive_request_id"),
            rs.getInt("source_version"),
            rs.getTimestamp("source_surfaced_at_utc") == null ? null
                    : rs.getTimestamp("source_surfaced_at_utc").toInstant(),
            rs.getInt("points_at_freeze"),
            rs.getTimestamp("planned_descent_at_utc").toInstant(),
            (Integer) rs.getObject("points_at_start"),
            rs.getBoolean("started"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ControlledFreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 方案变更：同事务清退该潜次旧冻结快照（旧方案不能再被实际下潜使用）。 */
    public int deleteByDive(String diveRequestId) {
        return jdbc.update("DELETE FROM controlled_plan_freeze WHERE dive_request_id = ?",
                diveRequestId);
    }

    public void insert(ControlledFreezeRow row) {
        jdbc.update("INSERT INTO controlled_plan_freeze "
                        + "(dive_request_id, version, diver_code, source_dive_request_id, "
                        + "source_version, source_surfaced_at_utc, points_at_freeze, "
                        + "planned_descent_at_utc, points_at_start, started, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.version(), row.diverCode(), row.sourceDiveRequestId(),
                row.sourceVersion(), ts(row.sourceSurfacedAt()), row.pointsAtFreeze(),
                Timestamp.from(row.plannedDescentAt()),
                row.pointsAtStart() == null ? null : row.pointsAtStart(),
                row.started(), Timestamp.from(row.createdAtUtc()));
    }

    public List<ControlledFreezeRow> findByDive(String diveRequestId) {
        return jdbc.query("SELECT * FROM controlled_plan_freeze WHERE dive_request_id = ? "
                        + "ORDER BY id", MAPPER, diveRequestId);
    }

    /** 事务内对该潜次冻结快照加行锁，供实际下潜重查。 */
    public List<ControlledFreezeRow> findByDiveForUpdate(String diveRequestId) {
        return jdbc.query("SELECT * FROM controlled_plan_freeze WHERE dive_request_id = ? "
                        + "ORDER BY id FOR UPDATE", MAPPER, diveRequestId);
    }

    /** 实际下潜重查通过：回写该组员下潜前积分并标记已占用。 */
    public void markStarted(String diveRequestId, String diverCode, int pointsAtStart) {
        jdbc.update("UPDATE controlled_plan_freeze SET started = TRUE, points_at_start = ? "
                        + "WHERE dive_request_id = ? AND diver_code = ?",
                pointsAtStart, diveRequestId, diverCode);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
