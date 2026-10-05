package com.example.starter.flood.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 泄流方案仓储；active_key 唯一索引保证同一尾矿库同时刻只有一个执行中方案。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<PlanPO> MAPPER = new RowMapper<>() {
        @Override
        public PlanPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            PlanPO po = new PlanPO();
            po.id = rs.getLong("id");
            po.planNo = rs.getString("plan_no");
            po.pondId = rs.getLong("pond_id");
            po.warningId = rs.getLong("warning_id");
            po.routingId = rs.getLong("routing_id");
            po.requestId = rs.getString("request_id");
            po.openings = rs.getString("openings");
            po.totalRelease = rs.getBigDecimal("total_release");
            po.downstreamSafeRelease = rs.getBigDecimal("downstream_safe_release");
            po.status = rs.getString("status");
            po.version = rs.getInt("version");
            long activeKey = rs.getLong("active_key");
            po.activeKey = rs.wasNull() ? null : activeKey;
            po.createdAt = toLdt(rs.getTimestamp("created_at"));
            po.completedAt = toLdt(rs.getTimestamp("completed_at"));
            po.supersededAt = toLdt(rs.getTimestamp("superseded_at"));
            po.archivedAt = toLdt(rs.getTimestamp("archived_at"));
            return po;
        }
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public void insert(PlanPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO release_plan(plan_no, pond_id, warning_id, routing_id, request_id, "
                            + "openings, total_release, downstream_safe_release, status, version, "
                            + "active_key, created_at, completed_at, superseded_at, archived_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.planNo);
            ps.setLong(2, po.pondId);
            ps.setLong(3, po.warningId);
            ps.setLong(4, po.routingId);
            ps.setString(5, po.requestId);
            ps.setString(6, po.openings);
            ps.setBigDecimal(7, po.totalRelease);
            ps.setBigDecimal(8, po.downstreamSafeRelease);
            ps.setString(9, po.status);
            ps.setInt(10, po.version);
            ps.setObject(11, po.activeKey);
            ps.setTimestamp(12, Timestamp.valueOf(po.createdAt));
            ps.setTimestamp(13, po.completedAt == null ? null : Timestamp.valueOf(po.completedAt));
            ps.setTimestamp(14, po.supersededAt == null ? null : Timestamp.valueOf(po.supersededAt));
            ps.setTimestamp(15, po.archivedAt == null ? null : Timestamp.valueOf(po.archivedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.id = key.longValue();
        }
    }

    public PlanPO findByNo(String planNo) {
        List<PlanPO> list = jdbcTemplate.query(
                "SELECT * FROM release_plan WHERE plan_no = ?", MAPPER, planNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public PlanPO findById(Long id) {
        List<PlanPO> list = jdbcTemplate.query(
                "SELECT * FROM release_plan WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public PlanPO findByRequestId(String requestId) {
        List<PlanPO> list = jdbcTemplate.query(
                "SELECT * FROM release_plan WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public PlanPO findActiveByPond(Long pondId) {
        List<PlanPO> list = jdbcTemplate.query(
                "SELECT * FROM release_plan WHERE active_key = ?", MAPPER, pondId);
        return list.isEmpty() ? null : list.get(0);
    }

    public PlanPO findByWarningId(Long warningId) {
        List<PlanPO> list = jdbcTemplate.query(
                "SELECT * FROM release_plan WHERE warning_id = ? ORDER BY id", MAPPER, warningId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询处置链全部方案版本（旧版本留痕可查，按版本号升序）。
     */
    public List<PlanPO> findAllByWarningId(Long warningId) {
        return jdbcTemplate.query(
                "SELECT * FROM release_plan WHERE warning_id = ? ORDER BY version, id",
                MAPPER, warningId);
    }

    /**
     * 处置链内当前最大方案版本号；无方案时返回 0。
     */
    public int maxVersionByWarningId(Long warningId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM release_plan WHERE warning_id = ?",
                Integer.class, warningId);
        return max == null ? 0 : max;
    }

    /**
     * 用新版本取代旧方案：解除执行中唯一位并置 SUPERSEDED（旧版本保留可查）。
     */
    public int markSuperseded(Long planId, LocalDateTime supersededAt) {
        return jdbcTemplate.update(
                "UPDATE release_plan SET status = 'SUPERSEDED', active_key = NULL, "
                        + "superseded_at = ? WHERE id = ? AND status = 'EFFECTIVE'",
                Timestamp.valueOf(supersededAt), planId);
    }

    /**
     * 回执闭环：解除执行中唯一位并置 COMPLETED。
     */
    public int markCompleted(Long planId, LocalDateTime completedAt) {
        return jdbcTemplate.update(
                "UPDATE release_plan SET status = 'COMPLETED', active_key = NULL, completed_at = ? "
                        + "WHERE id = ? AND status = 'EFFECTIVE'",
                Timestamp.valueOf(completedAt), planId);
    }

    /**
     * 归档终态。
     */
    public int markArchived(Long planId, LocalDateTime archivedAt) {
        return jdbcTemplate.update(
                "UPDATE release_plan SET status = 'ARCHIVED', archived_at = ? WHERE id = ?",
                Timestamp.valueOf(archivedAt), planId);
    }
}
