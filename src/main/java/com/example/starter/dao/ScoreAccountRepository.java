package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 潜水员反复潜水积分账户访问（虚构积分模型，不用于真实潜水减压）。
 *
 * <p>账户行在首次受控方案受理时创建（版本 1、残留积分 0、无出水记录）；
 * 每次受控出水结算用“带版本的条件更新”原子推进版本，并发结算由数据库唯一受理。</p>
 */
@Repository
public class ScoreAccountRepository {

    private static final RowMapper<ScoreAccountRow> MAPPER = (rs, n) -> new ScoreAccountRow(
            rs.getString("diver_code"),
            rs.getInt("version"),
            rs.getInt("residual_score"),
            rs.getTimestamp("last_surfaced_at_utc") == null ? null
                    : rs.getTimestamp("last_surfaced_at_utc").toInstant(),
            rs.getString("last_dive_request_id"),
            rs.getString("active_dive_request_id"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ScoreAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScoreAccountRow> find(String diverCode) {
        return jdbc.query("SELECT * FROM diver_score_account WHERE diver_code = ?",
                MAPPER, diverCode).stream().findFirst();
    }

    /** 首次受控方案：创建账户（版本 1、残留 0），并登记占用潜次。 */
    public void insert(ScoreAccountRow row) {
        jdbc.update("INSERT INTO diver_score_account "
                        + "(diver_code, version, residual_score, last_surfaced_at_utc, "
                        + "last_dive_request_id, active_dive_request_id, created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.diverCode(), row.version(), row.residualScore(),
                ts(row.lastSurfacedAtUtc()), row.lastDiveRequestId(), row.activeDiveRequestId(),
                ts(row.createdAtUtc()), ts(row.updatedAtUtc()));
    }

    /** 受控方案重做：方案仍占用该潜水员（不改变积分与出水来源）。 */
    public void markActive(String diverCode, String diveRequestId, Instant now) {
        jdbc.update("UPDATE diver_score_account SET active_dive_request_id = ?, updated_at_utc = ? "
                        + "WHERE diver_code = ?",
                diveRequestId, ts(now), diverCode);
    }

    /**
     * 受控出水原子结算：仅当账户版本仍等于 expectedVersion 时更新，
     * 残留积分/出水时刻/来源潜次/版本（+1）一次写入并清空占用。
     *
     * @return 更新行数；0 表示版本已被并发结算推进，调用方须按 409 回滚
     */
    public int settle(String diverCode, int expectedVersion, int residualScore,
                      Instant surfacedAt, String diveRequestId, int newVersion, Instant now) {
        return jdbc.update("UPDATE diver_score_account SET residual_score = ?, "
                        + "last_surfaced_at_utc = ?, last_dive_request_id = ?, "
                        + "active_dive_request_id = NULL, version = ?, updated_at_utc = ? "
                        + "WHERE diver_code = ? AND version = ?",
                residualScore, ts(surfacedAt), diveRequestId, newVersion, ts(now),
                diverCode, expectedVersion);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
