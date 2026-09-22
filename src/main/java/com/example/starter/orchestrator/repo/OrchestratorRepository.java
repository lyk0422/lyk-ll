package com.example.starter.orchestrator.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 编排器所有表的 JDBC 访问（H2 MODE=MySQL，参数化 SQL）。
 */
@Repository
public class OrchestratorRepository {

    private final JdbcTemplate jdbc;

    public OrchestratorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"),
            rs.getString("plan_id"),
            rs.getString("version"),
            rs.getString("request_id"),
            rs.getString("plan_name"),
            rs.getString("status"),
            rs.getString("payload_hash"),
            rs.getInt("cancel_requested") == 1,
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private static final RowMapper<NodeRow> NODE_MAPPER = (rs, n) -> new NodeRow(
            rs.getLong("id"),
            rs.getString("plan_id"),
            rs.getString("node_id"),
            rs.getString("node_name"),
            rs.getInt("order_index"),
            rs.getLong("launch_delay_ms"),
            rs.getInt("simulate_start_failure") == 1,
            rs.getInt("health_should_fail") == 1,
            rs.getString("health_message"),
            rs.getString("compensation"),
            rs.getInt("simulate_comp_failure") == 1,
            rs.getString("state"),
            rs.getInt("compensated") == 1,
            rs.getString("failure_reason"),
            rs.getString("health_status"),
            toLocalDateTime(rs.getTimestamp("started_at")),
            toLocalDateTime(rs.getTimestamp("finished_at"))
    );

    private static final RowMapper<ParamRow> PARAM_MAPPER = (rs, n) -> new ParamRow(
            rs.getString("plan_id"),
            rs.getString("node_id"),
            rs.getString("param_key"),
            rs.getString("param_value")
    );

    private static final RowMapper<DependencyRow> DEP_MAPPER = (rs, n) -> new DependencyRow(
            rs.getString("plan_id"),
            rs.getString("node_id"),
            rs.getString("depends_on"),
            rs.getString("dep_kind")
    );

    private static final RowMapper<ConditionRow> CONDITION_MAPPER = (rs, n) -> new ConditionRow(
            rs.getString("plan_id"),
            rs.getString("node_id"),
            rs.getString("predicate"),
            rs.getString("ref_node_id"),
            rs.getString("param_name"),
            rs.getString("expected_value")
    );

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"),
            rs.getString("plan_id"),
            rs.getString("version"),
            rs.getString("request_id"),
            rs.getString("node_id"),
            rs.getString("event_type"),
            rs.getString("detail"),
            rs.getTimestamp("event_time").toLocalDateTime()
    );

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ---------- launch_plan ----------

    public PlanRow findPlanByRequestId(String requestId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM launch_plan WHERE request_id = ?", PLAN_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PlanRow findPlanByPlanId(String planId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM launch_plan WHERE plan_id = ?", PLAN_MAPPER, planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertPlan(PlanRow row) {
        jdbc.update("""
                INSERT INTO launch_plan
                  (plan_id, version, request_id, plan_name, status, payload_hash,
                   cancel_requested, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.planId(), row.version(), row.requestId(), row.planName(), row.status(),
                row.payloadHash(), row.cancelRequested() ? 1 : 0,
                Timestamp.valueOf(row.createdAt()), Timestamp.valueOf(row.updatedAt()));
    }

    public void updatePlanStatus(String planId, String status, LocalDateTime updatedAt) {
        jdbc.update("UPDATE launch_plan SET status = ?, updated_at = ? WHERE plan_id = ?",
                status, Timestamp.valueOf(updatedAt), planId);
    }

    /** 将 cancel_requested 置 1，返回受影响行数（0 表示计划不存在）。 */
    public int markCancelRequested(String planId, LocalDateTime updatedAt) {
        return jdbc.update(
                "UPDATE launch_plan SET cancel_requested = 1, updated_at = ? WHERE plan_id = ?",
                Timestamp.valueOf(updatedAt), planId);
    }

    // ---------- plan_node ----------

    public void insertNode(NodeRow row) {
        jdbc.update("""
                INSERT INTO plan_node
                  (plan_id, node_id, node_name, order_index, launch_delay_ms,
                   simulate_start_failure, health_should_fail, health_message,
                   compensation, simulate_comp_failure, state, compensated,
                   failure_reason, health_status, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.planId(), row.nodeId(), row.nodeName(), row.orderIndex(), row.launchDelayMs(),
                row.simulateStartFailure() ? 1 : 0, row.healthShouldFail() ? 1 : 0,
                row.healthMessage(), row.compensation(), row.simulateCompFailure() ? 1 : 0,
                row.state(), row.compensated() ? 1 : 0, row.failureReason(), row.healthStatus(),
                row.startedAt() == null ? null : Timestamp.valueOf(row.startedAt()),
                row.finishedAt() == null ? null : Timestamp.valueOf(row.finishedAt()));
    }

    public List<NodeRow> findNodesByPlanId(String planId) {
        return jdbc.query(
                "SELECT * FROM plan_node WHERE plan_id = ? ORDER BY order_index, id",
                NODE_MAPPER, planId);
    }

    /** 条件化更新节点运行时字段。 */
    public void updateNodeRuntime(NodeRow row) {
        jdbc.update("""
                UPDATE plan_node SET
                  state = ?, compensated = ?, failure_reason = ?, health_status = ?,
                  started_at = ?, finished_at = ?
                WHERE plan_id = ? AND node_id = ?
                """,
                row.state(), row.compensated() ? 1 : 0, row.failureReason(), row.healthStatus(),
                row.startedAt() == null ? null : Timestamp.valueOf(row.startedAt()),
                row.finishedAt() == null ? null : Timestamp.valueOf(row.finishedAt()),
                row.planId(), row.nodeId());
    }

    // ---------- node_param ----------

    public void insertParam(ParamRow row) {
        jdbc.update(
                "INSERT INTO node_param (plan_id, node_id, param_key, param_value) VALUES (?, ?, ?, ?)",
                row.planId(), row.nodeId(), row.paramKey(), row.paramValue());
    }

    public List<ParamRow> findParamsByPlanId(String planId) {
        return jdbc.query(
                "SELECT * FROM node_param WHERE plan_id = ? ORDER BY id", PARAM_MAPPER, planId);
    }

    // ---------- node_dependency ----------

    public void insertDependency(DependencyRow row) {
        jdbc.update("""
                INSERT INTO node_dependency (plan_id, node_id, depends_on, dep_kind)
                VALUES (?, ?, ?, ?)
                """, row.planId(), row.nodeId(), row.dependsOn(), row.depKind());
    }

    public List<DependencyRow> findDependenciesByPlanId(String planId) {
        return jdbc.query(
                "SELECT * FROM node_dependency WHERE plan_id = ? ORDER BY id",
                DEP_MAPPER, planId);
    }

    // ---------- node_condition ----------

    public void insertCondition(ConditionRow row) {
        jdbc.update("""
                INSERT INTO node_condition
                  (plan_id, node_id, predicate, ref_node_id, param_name, expected_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                row.planId(), row.nodeId(), row.predicate(), row.refNodeId(),
                row.paramName(), row.expectedValue());
    }

    public List<ConditionRow> findConditionsByPlanId(String planId) {
        return jdbc.query(
                "SELECT * FROM node_condition WHERE plan_id = ? ORDER BY id",
                CONDITION_MAPPER, planId);
    }

    // ---------- plan_event ----------

    public void insertEvent(EventRow row) {
        jdbc.update("""
                INSERT INTO plan_event
                  (plan_id, version, request_id, node_id, event_type, detail, event_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                row.planId(), row.version(), row.requestId(), row.nodeId(),
                row.eventType(), row.detail(), Timestamp.valueOf(row.eventTime()));
    }

    public List<EventRow> findEventsByPlanId(String planId) {
        return jdbc.query(
                "SELECT * FROM plan_event WHERE plan_id = ? ORDER BY id", EVENT_MAPPER, planId);
    }

    /** 测试场景间清理数据，避免顺序依赖。 */
    public void deleteAllData() {
        jdbc.update("DELETE FROM plan_event");
        jdbc.update("DELETE FROM node_condition");
        jdbc.update("DELETE FROM node_dependency");
        jdbc.update("DELETE FROM node_param");
        jdbc.update("DELETE FROM plan_node");
        jdbc.update("DELETE FROM launch_plan");
    }
}
