package com.example.starter.water.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.water.domain.PlanRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 月度计划版本与申报幂等记录的 JdbcTemplate/H2 实现。
 */
@Repository
public class JdbcPlanRepository implements PlanRepository {

    private static final RowMapper<PlanVersionRow> VERSION_MAPPER = (rs, n) -> {
        long restrictionIdValue = rs.getLong("restriction_id");
        Long restrictionId = rs.wasNull() ? null : restrictionIdValue;
        return new PlanVersionRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("plan_month"),
                rs.getInt("version_no"),
                rs.getBigDecimal("declared_volume"),
                rs.getBigDecimal("accepted_volume"),
                rs.getBoolean("restricted"),
                restrictionId,
                rs.getString("request_id"),
                rs.getTimestamp("confirmed_at").toInstant());
    };

    private static final RowMapper<IdempotencyRow> IDEMPOTENCY_MAPPER = (rs, n) -> {
        long planVersionIdValue = rs.getLong("plan_version_id");
        Long planVersionId = rs.wasNull() ? null : planVersionIdValue;
        String rejectReason = rs.getString("reject_reason");
        return new IdempotencyRow(
                rs.getString("request_id"),
                rs.getLong("user_id"),
                rs.getString("idem_month"),
                rs.getBigDecimal("declared_volume"),
                planVersionId,
                rs.getBoolean("rejected"),
                rejectReason,
                rs.getTimestamp("first_seen_at").toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT request_id, user_id, idem_month, declared_volume, plan_version_id, rejected, "
                            + "reject_reason, first_seen_at FROM declaration_idempotency WHERE request_id = ?",
                    IDEMPOTENCY_MAPPER, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void insertIdempotency(IdempotencyRow row) {
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO declaration_idempotency (request_id, user_id, idem_month, declared_volume, "
                            + "plan_version_id, rejected, reject_reason, first_seen_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, row.requestId());
            ps.setLong(2, row.userId());
            ps.setString(3, row.month());
            ps.setBigDecimal(4, row.declaredVolume());
            if (row.planVersionId() == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, row.planVersionId());
            }
            ps.setBoolean(6, row.rejected());
            ps.setString(7, row.rejectReason());
            ps.setTimestamp(8, Timestamp.from(row.firstSeenAt()));
            return ps;
        });
    }

    @Override
    public PlanVersionRow appendVersion(Long userId, String month, BigDecimal declaredVolume,
                                        BigDecimal acceptedVolume, boolean restricted, Long restrictionId,
                                        String requestId, Instant confirmedAt) {
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM plan_version WHERE user_id = ? AND plan_month = ?",
                Integer.class, userId, month);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO plan_version (user_id, plan_month, version_no, declared_volume, accepted_volume, "
                            + "restricted, restriction_id, request_id, confirmed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, month);
            ps.setInt(3, nextVersion);
            ps.setBigDecimal(4, declaredVolume);
            ps.setBigDecimal(5, acceptedVolume);
            ps.setBoolean(6, restricted);
            if (restrictionId == null) {
                ps.setObject(7, null);
            } else {
                ps.setLong(7, restrictionId);
            }
            ps.setString(8, requestId);
            ps.setTimestamp(9, Timestamp.from(confirmedAt));
            return ps;
        }, keyHolder);
        return new PlanVersionRow(keyHolder.getKey().longValue(), userId, month, nextVersion,
                declaredVolume, acceptedVolume, restricted, restrictionId, requestId, confirmedAt);
    }

    @Override
    public Optional<PlanVersionRow> findLatestVersion(Long userId, String month) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, user_id, plan_month, version_no, declared_volume, accepted_volume, restricted, "
                            + "restriction_id, request_id, confirmed_at FROM plan_version "
                            + "WHERE user_id = ? AND plan_month = ? ORDER BY version_no DESC FETCH FIRST 1 ROWS ONLY",
                    VERSION_MAPPER, userId, month));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<PlanVersionRow> findLatestVersionsOfYear(Long userId, int year) {
        // 每月只取最后已确认版本：按 version 分组取最大版本后回连。
        return jdbcTemplate.query(
                "SELECT pv.id, pv.user_id, pv.plan_month, pv.version_no, pv.declared_volume, pv.accepted_volume, "
                        + "pv.restricted, pv.restriction_id, pv.request_id, pv.confirmed_at "
                        + "FROM plan_version pv "
                        + "JOIN (SELECT plan_month, MAX(version_no) AS max_version FROM plan_version "
                        + "      WHERE user_id = ? AND plan_month LIKE ? GROUP BY plan_month) m "
                        + "ON pv.plan_month = m.plan_month AND pv.version_no = m.max_version AND pv.user_id = ? "
                        + "ORDER BY pv.plan_month",
                VERSION_MAPPER, userId, year + "-%", userId);
    }

    @Override
    public Optional<PlanVersionRow> findVersionById(Long id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, user_id, plan_month, version_no, declared_volume, accepted_volume, restricted, "
                            + "restriction_id, request_id, confirmed_at FROM plan_version WHERE id = ?",
                    VERSION_MAPPER, new Object[] {id}));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
