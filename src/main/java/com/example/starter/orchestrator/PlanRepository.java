package com.example.starter.orchestrator;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.orchestrator.dto.EventView;

/**
 * 编排计划持久化层，基于 JdbcTemplate 与参数化 SQL 访问 H2。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 计划持久化记录。
     */
    public record PlanRow(String planId, int version, String requestId, String payloadHash,
            PlanStatus status, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 节点持久化记录。
     */
    public record NodeRow(String planId, String nodeKey, String paramsJson, long delayMillis,
            String conditionRef, String conditionExpect, String depsJson, String healthCheck,
            String compensation, NodeState state, boolean compensated) {
    }

    public void insertPlan(PlanRow plan) {
        jdbc.update("INSERT INTO launch_plan(plan_id, plan_version, request_id, payload_hash, status, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                plan.planId(), plan.version(), plan.requestId(), plan.payloadHash(), plan.status().name(),
                Timestamp.from(plan.createdAt()), Timestamp.from(plan.updatedAt()));
    }

    public Optional<PlanRow> findPlanByRequestId(String requestId) {
        List<PlanRow> rows = jdbc.query("SELECT * FROM launch_plan WHERE request_id = ?",
                this::mapPlan, requestId);
        return rows.stream().findFirst();
    }

    public Optional<PlanRow> findPlan(String planId) {
        List<PlanRow> rows = jdbc.query("SELECT * FROM launch_plan WHERE plan_id = ?",
                this::mapPlan, planId);
        return rows.stream().findFirst();
    }

    public void updatePlanStatus(String planId, PlanStatus status, Instant updatedAt) {
        jdbc.update("UPDATE launch_plan SET status = ?, updated_at = ? WHERE plan_id = ?",
                status.name(), Timestamp.from(updatedAt), planId);
    }

    public void insertNode(NodeRow node) {
        jdbc.update("INSERT INTO launch_node(plan_id, node_key, params_json, delay_millis, condition_ref,"
                        + " condition_expect, deps_json, health_check, compensation, state, compensated)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                node.planId(), node.nodeKey(), node.paramsJson(), node.delayMillis(), node.conditionRef(),
                node.conditionExpect(), node.depsJson(), node.healthCheck(), node.compensation(),
                node.state().name(), node.compensated());
    }

    public List<NodeRow> findNodes(String planId) {
        return jdbc.query("SELECT * FROM launch_node WHERE plan_id = ? ORDER BY node_key",
                this::mapNode, planId);
    }

    public Optional<NodeRow> findNode(String planId, String nodeKey) {
        List<NodeRow> rows = jdbc.query("SELECT * FROM launch_node WHERE plan_id = ? AND node_key = ?",
                this::mapNode, planId, nodeKey);
        return rows.stream().findFirst();
    }

    public void updateNodeState(String planId, String nodeKey, NodeState state) {
        jdbc.update("UPDATE launch_node SET state = ? WHERE plan_id = ? AND node_key = ?",
                state.name(), planId, nodeKey);
    }

    /**
     * 仅当补偿未执行过时标记为已补偿，返回是否由本次调用完成标记（保证补偿只执行一次）。
     */
    public boolean markCompensated(String planId, String nodeKey) {
        return jdbc.update("UPDATE launch_node SET compensated = TRUE WHERE plan_id = ? AND node_key = ?"
                + " AND compensated = FALSE", planId, nodeKey) == 1;
    }

    public void insertEvent(String planId, String nodeKey, EventType type, String detail, Instant createdAt) {
        jdbc.update("INSERT INTO launch_event(plan_id, node_key, event_type, detail, created_at)"
                        + " VALUES (?,?,?,?,?)",
                planId, nodeKey, type.name(), detail, Timestamp.from(createdAt));
    }

    public List<EventView> findEvents(String planId) {
        return jdbc.query("SELECT * FROM launch_event WHERE plan_id = ? ORDER BY id",
                (rs, n) -> new EventView(rs.getLong("id"), rs.getString("node_key"),
                        rs.getString("event_type"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                planId);
    }

    public long countEvents(String planId, String nodeKey, EventType type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM launch_event WHERE plan_id = ? AND node_key = ? AND event_type = ?",
                Long.class, planId, nodeKey, type.name());
        return count == null ? 0 : count;
    }

    private PlanRow mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new PlanRow(rs.getString("plan_id"), rs.getInt("plan_version"), rs.getString("request_id"),
                rs.getString("payload_hash"), PlanStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private NodeRow mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new NodeRow(rs.getString("plan_id"), rs.getString("node_key"), rs.getString("params_json"),
                rs.getLong("delay_millis"), rs.getString("condition_ref"), rs.getString("condition_expect"),
                rs.getString("deps_json"), rs.getString("health_check"), rs.getString("compensation"),
                NodeState.valueOf(rs.getString("state")), rs.getBoolean("compensated"));
    }
}
