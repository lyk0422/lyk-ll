package com.example.starter.waterlogging.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.PreDrainPlan;

/**
 * 预降决策单数据访问。
 */
@Repository
public class PlanRepository {

    private static final String COLUMNS = """
            id, request_id, basin_zone, warning_level_code, river_name, target_level,
            warning_time, gate_opening, status, started_at, completed_at, archived_at,
            fingerprint, created_at
            """;

    private static final RowMapper<PreDrainPlan> MAPPER = (rs, n) -> new PreDrainPlan(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("basin_zone"),
            rs.getString("warning_level_code"),
            rs.getString("river_name"),
            rs.getBigDecimal("target_level"),
            readTime(rs, "warning_time"),
            rs.getBigDecimal("gate_opening"),
            rs.getString("status"),
            readTime(rs, "started_at"),
            readTime(rs, "completed_at"),
            readTime(rs, "archived_at"),
            rs.getString("fingerprint"),
            readTime(rs, "created_at"));

    public PlanRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static LocalDateTime readTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    /** 新建预降单，返回自增主键。 */
    public long insert(PreDrainPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO pre_drain_plan
                        (request_id, basin_zone, warning_level_code, river_name, target_level,
                         warning_time, gate_opening, status, started_at, completed_at, archived_at,
                         fingerprint, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.requestId());
            ps.setString(2, plan.basinZone());
            ps.setString(3, plan.warningLevelCode());
            ps.setString(4, plan.riverName());
            ps.setBigDecimal(5, plan.targetLevel());
            ps.setTimestamp(6, Timestamp.valueOf(plan.warningTime()));
            ps.setBigDecimal(7, plan.gateOpening());
            ps.setString(8, plan.status());
            ps.setTimestamp(9, plan.startedAt() == null ? null : Timestamp.valueOf(plan.startedAt()));
            ps.setTimestamp(10, plan.completedAt() == null ? null : Timestamp.valueOf(plan.completedAt()));
            ps.setTimestamp(11, plan.archivedAt() == null ? null : Timestamp.valueOf(plan.archivedAt()));
            ps.setString(12, plan.fingerprint());
            ps.setTimestamp(13, Timestamp.valueOf(plan.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按预警 requestId 查询。 */
    public Optional<PreDrainPlan> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM pre_drain_plan WHERE request_id = ?",
                    MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 按主键查询。 */
    public Optional<PreDrainPlan> findById(long planId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM pre_drain_plan WHERE id = ?", MAPPER, planId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询某分区最近一张预降单（任意状态），按预警时刻倒序。 */
    public Optional<PreDrainPlan> findLatestByZone(String basinZone) {
        List<PreDrainPlan> plans = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM pre_drain_plan WHERE basin_zone = ? "
                        + "ORDER BY warning_time DESC, id DESC LIMIT 1",
                MAPPER, basinZone);
        return plans.stream().findFirst();
    }

    /** 行级锁定预降单，串行化同单状态推进。 */
    public Optional<PreDrainPlan> findByIdForUpdate(long planId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM pre_drain_plan WHERE id = ? FOR UPDATE",
                    MAPPER, planId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 更新状态为 COMPLETED 并记录完成时刻。 */
    public int markCompleted(long planId, LocalDateTime completedAt) {
        return jdbcTemplate.update(
                "UPDATE pre_drain_plan SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.valueOf(completedAt), planId);
    }

    /** 更新状态为 ARCHIVED 终态并记录归档时刻。 */
    public int markArchived(long planId, LocalDateTime archivedAt) {
        return jdbcTemplate.update(
                "UPDATE pre_drain_plan SET status = 'ARCHIVED', archived_at = ? WHERE id = ?",
                Timestamp.valueOf(archivedAt), planId);
    }
}
