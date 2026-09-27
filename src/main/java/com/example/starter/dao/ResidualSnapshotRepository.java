package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 受控方案积分来源冻结快照访问。方案时刻逐人冻结最近潜次版本、出水时刻、
 * 出水残留积分与账户版本；下潜前据此重查来源未变。
 */
@Repository
public class ResidualSnapshotRepository {

    private static final RowMapper<ResidualSnapshotRow> MAPPER = (rs, n) -> new ResidualSnapshotRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getInt("plan_version"),
            rs.getString("diver_code"),
            rs.getString("source_dive_request_id"),
            (Integer) rs.getObject("source_plan_version"),
            rs.getTimestamp("source_surfaced_at_utc") == null ? null
                    : rs.getTimestamp("source_surfaced_at_utc").toInstant(),
            (Integer) rs.getObject("source_residual_points"),
            (Integer) rs.getObject("source_account_version"),
            rs.getInt("frozen_points"),
            rs.getInt("ndl_minutes"),
            rs.getInt("allowed_minutes"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ResidualSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ResidualSnapshotRow row) {
        jdbc.update("INSERT INTO residual_plan_snapshot "
                        + "(dive_request_id, plan_version, diver_code, source_dive_request_id, "
                        + "source_plan_version, source_surfaced_at_utc, source_residual_points, "
                        + "source_account_version, frozen_points, ndl_minutes, allowed_minutes, "
                        + "created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.planVersion(), row.diverCode(),
                row.sourceDiveRequestId(), row.sourcePlanVersion(),
                row.sourceSurfacedAtUtc() == null ? null
                        : Timestamp.from(row.sourceSurfacedAtUtc()),
                row.sourceResidualPoints(), row.sourceAccountVersion(),
                row.frozenPoints(), row.ndlMinutes(), row.allowedMinutes(),
                Timestamp.from(row.createdAtUtc()));
    }

    /** 方案变更重做：同事务清退旧版本快照（与旧方案冻结一致）。 */
    public int deleteByDive(String diveRequestId) {
        return jdbc.update("DELETE FROM residual_plan_snapshot WHERE dive_request_id = ?",
                diveRequestId);
    }

    public List<ResidualSnapshotRow> findByVersion(String diveRequestId, int planVersion) {
        return jdbc.query("SELECT * FROM residual_plan_snapshot WHERE dive_request_id = ? "
                        + "AND plan_version = ? ORDER BY diver_code",
                MAPPER, diveRequestId, planVersion);
    }

    public Optional<ResidualSnapshotRow> find(String diveRequestId, int planVersion,
                                              String diverCode) {
        return jdbc.query("SELECT * FROM residual_plan_snapshot WHERE dive_request_id = ? "
                        + "AND plan_version = ? AND diver_code = ?",
                MAPPER, diveRequestId, planVersion, diverCode).stream().findFirst();
    }

    public ResidualSnapshotRow get(String diveRequestId, int planVersion, String diverCode) {
        return find(diveRequestId, planVersion, diverCode).orElseThrow(() ->
                new IllegalArgumentException("受控方案积分快照不存在: "
                        + diveRequestId + "#v" + planVersion + "#" + diverCode));
    }
}
