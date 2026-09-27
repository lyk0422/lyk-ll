package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 潜水员余量积分账户访问。仅受控出水登记在单事务内逐人加行锁后原子更新，
 * 版本号每次成功出水加 1，避免旧方案/并发事务消费或覆盖新出水积分。
 */
@Repository
public class ResidualAccountRepository {

    private static final RowMapper<ResidualAccountRow> MAPPER = (rs, n) -> new ResidualAccountRow(
            rs.getString("diver_code"),
            rs.getTimestamp("last_surfaced_at_utc").toInstant(),
            rs.getInt("residual_points"),
            rs.getString("source_dive_request_id"),
            rs.getInt("source_plan_version"),
            rs.getInt("version"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ResidualAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ResidualAccountRow> find(String diverCode) {
        return jdbc.query("SELECT * FROM residual_point_account WHERE diver_code = ?",
                MAPPER, diverCode).stream().findFirst();
    }

    /** 事务内加行锁读取，供受控出水原子更新两人账户。 */
    public Optional<ResidualAccountRow> findForUpdate(String diverCode) {
        return jdbc.query("SELECT * FROM residual_point_account WHERE diver_code = ? FOR UPDATE",
                MAPPER, diverCode).stream().findFirst();
    }

    public void insert(ResidualAccountRow row) {
        jdbc.update("INSERT INTO residual_point_account "
                        + "(diver_code, last_surfaced_at_utc, residual_points, "
                        + "source_dive_request_id, source_plan_version, version, "
                        + "created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.diverCode(), Timestamp.from(row.lastSurfacedAtUtc()), row.residualPoints(),
                row.sourceDiveRequestId(), row.sourcePlanVersion(), row.version(),
                Timestamp.from(row.createdAtUtc()), Timestamp.from(row.updatedAtUtc()));
    }

    /**
     * 受控出水原子更新：必须命中 {@code expectedVersion}（乐观锁），更新后版本加 1。
     * 返回受影响行数；0 表示来源账户在方案冻结后已被其他潜次出水改动，调用方须整体回滚重做。
     */
    public int updateForSurface(String diverCode, Instant surfacedAt, int residualPoints,
                                String sourceDiveRequestId, int sourcePlanVersion,
                                int expectedVersion, Instant now) {
        return jdbc.update("UPDATE residual_point_account SET last_surfaced_at_utc = ?, "
                        + "residual_points = ?, source_dive_request_id = ?, source_plan_version = ?, "
                        + "version = version + 1, updated_at_utc = ? "
                        + "WHERE diver_code = ? AND version = ?",
                Timestamp.from(surfacedAt), residualPoints, sourceDiveRequestId,
                sourcePlanVersion, Timestamp.from(now), diverCode, expectedVersion);
    }
}
