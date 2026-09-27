package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 受控出水积分结算台账访问（与 bottom_time_ledger 并存）。
 */
@Repository
public class ScoreSettlementRepository {

    private static final RowMapper<ScoreSettlementRow> MAPPER = (rs, n) -> new ScoreSettlementRow(
            rs.getLong("id"),
            rs.getString("diver_code"),
            rs.getString("dive_request_id"),
            rs.getInt("plan_version"),
            rs.getInt("pre_dive_score"),
            rs.getInt("bottom_min"),
            rs.getInt("residual_score"),
            rs.getTimestamp("last_surfaced_at_utc").toInstant(),
            rs.getTimestamp("settled_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ScoreSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ScoreSettlementRow row) {
        jdbc.update("INSERT INTO score_settlement "
                        + "(diver_code, dive_request_id, plan_version, pre_dive_score, bottom_min, "
                        + "residual_score, last_surfaced_at_utc, settled_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.diverCode(), row.diveRequestId(), row.planVersion(), row.preDiveScore(),
                row.bottomMin(), row.residualScore(), Timestamp.from(row.lastSurfacedAtUtc()),
                Timestamp.from(row.settledAtUtc()));
    }

    public List<ScoreSettlementRow> findByDive(String diveRequestId) {
        return jdbc.query("SELECT * FROM score_settlement WHERE dive_request_id = ? ORDER BY diver_code",
                MAPPER, diveRequestId);
    }
}
