package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 积分变动流水访问。{@code (dive_request_id, diver_code)} 唯一：
 * 受控出水幂等重试不会为同一潜次同一人重复记账。
 */
@Repository
public class ResidualMovementRepository {

    private static final RowMapper<ResidualMovementRow> MAPPER = (rs, n) -> new ResidualMovementRow(
            rs.getLong("id"),
            rs.getString("diver_code"),
            rs.getString("dive_request_id"),
            rs.getInt("plan_version"),
            rs.getInt("pre_dive_points"),
            rs.getInt("bottom_min"),
            rs.getInt("residual_points"),
            rs.getTimestamp("surfaced_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ResidualMovementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ResidualMovementRow row) {
        jdbc.update("INSERT INTO residual_point_movement "
                        + "(diver_code, dive_request_id, plan_version, pre_dive_points, bottom_min, "
                        + "residual_points, surfaced_at_utc, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.diverCode(), row.diveRequestId(), row.planVersion(), row.preDivePoints(),
                row.bottomMin(), row.residualPoints(), Timestamp.from(row.surfacedAtUtc()),
                Timestamp.from(row.createdAtUtc()));
    }

    public List<ResidualMovementRow> findByDive(String diveRequestId) {
        return jdbc.query("SELECT * FROM residual_point_movement WHERE dive_request_id = ? "
                        + "ORDER BY id",
                MAPPER, diveRequestId);
    }
}
