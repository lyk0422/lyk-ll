package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.PlanCount;
import com.example.starter.domain.PlanRow;
import com.example.starter.domain.PlanStatus;

/**
 * 修剪计划数据访问。
 */
@Repository
public class PlanRepository {

    private static final RowMapper<PlanRow> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static PlanRow map(ResultSet rs) throws SQLException {
        Boolean onTime = rs.getObject("closed_on_time") == null ? null : rs.getBoolean("closed_on_time");
        return new PlanRow(rs.getLong("id"), rs.getLong("tree_id"), rs.getLong("segment_id"),
                rs.getInt("risk_level"), PlanStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("deadline", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class), onTime);
    }

    public long insert(long treeId, long segmentId, int riskLevel, PlanStatus status,
                       LocalDateTime createdAt, LocalDateTime deadline) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO pruning_plan(tree_id, segment_id, risk_level, status, created_at, deadline)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, treeId);
            ps.setLong(2, segmentId);
            ps.setInt(3, riskLevel);
            ps.setString(4, status.name());
            ps.setObject(5, createdAt);
            ps.setObject(6, deadline);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<PlanRow> findById(long id) {
        return jdbc.query("SELECT * FROM pruning_plan WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 树木当前未终结的计划（待修剪或已督办）。
     */
    public List<PlanRow> findOpenByTree(long treeId) {
        return jdbc.query("SELECT * FROM pruning_plan WHERE tree_id = ? AND status IN ('PENDING','ESCALATED')",
                MAPPER, treeId);
    }

    public void updateStatus(long id, PlanStatus status) {
        jdbc.update("UPDATE pruning_plan SET status = ? WHERE id = ?", status.name(), id);
    }

    public void close(long id, LocalDateTime closedAt, boolean onTime) {
        jdbc.update("UPDATE pruning_plan SET status = 'CLOSED', closed_at = ?, closed_on_time = ? WHERE id = ?",
                closedAt, onTime ? 1 : 0, id);
    }

    /**
     * 逾期升级督办：期限已过（严格大于期限才算逾期，恰第7日闭环有效）仍未闭环的计划置为 ESCALATED。
     */
    public int escalateOverdue(LocalDateTime now) {
        return jdbc.update("UPDATE pruning_plan SET status = 'ESCALATED'"
                + " WHERE status = 'PENDING' AND deadline < ?", now);
    }

    /**
     * 各路段计划状态分布，用于台账闭环率复算。
     */
    public List<PlanCount> countBySegmentAndStatus() {
        return jdbc.query(
                "SELECT segment_id, status, COUNT(*) AS cnt FROM pruning_plan"
                        + " GROUP BY segment_id, status",
                (rs, n) -> new PlanCount(rs.getLong("segment_id"), rs.getString("status"),
                        rs.getLong("cnt")));
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pruning_plan", Long.class);
    }
}
