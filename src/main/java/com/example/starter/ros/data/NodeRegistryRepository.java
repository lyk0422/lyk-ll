package com.example.starter.ros.data;

import com.example.starter.ros.domain.EventPo;
import com.example.starter.ros.domain.LifecycleState;
import com.example.starter.ros.domain.NodePo;
import com.example.starter.ros.domain.ResourceCategory;
import com.example.starter.ros.domain.ResourcePo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 注册中心数据访问层：节点、资源、事件、幂等记录、revision 序列与检查点。
 * 所有 SQL 均使用参数化占位符，兼容 H2 MODE=MySQL。
 */
@Repository
public class NodeRegistryRepository {

    private final JdbcTemplate jdbcTemplate;

    public NodeRegistryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 节点 ====================

    public Optional<NodePo> findNode(String nodeId) {
        List<NodePo> rows = jdbcTemplate.query(
                "SELECT node_id, namespace, node_name, lifecycle_state, version, alive, "
                        + "registered_at, updated_at FROM ros_node WHERE node_id = ?",
                NODE_MAPPER, nodeId);
        return rows.stream().findFirst();
    }

    /**
     * 事务内锁定节点行（SELECT ... FOR UPDATE），用于生命周期迁移的读改写。
     */
    public Optional<NodePo> findNodeForUpdate(String nodeId) {
        List<NodePo> rows = jdbcTemplate.query(
                "SELECT node_id, namespace, node_name, lifecycle_state, version, alive, "
                        + "registered_at, updated_at FROM ros_node WHERE node_id = ? FOR UPDATE",
                NODE_MAPPER, nodeId);
        return rows.stream().findFirst();
    }

    public void insertNode(NodePo node) {
        jdbcTemplate.update(
                "INSERT INTO ros_node (node_id, namespace, node_name, lifecycle_state, version, alive, "
                        + "registered_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                node.nodeId(), node.namespace(), node.nodeName(),
                node.lifecycleState().name(), node.version(), node.alive() ? 1 : 0,
                Timestamp.from(node.registeredAt()), Timestamp.from(node.updatedAt()));
    }

    /**
     * 崩溃节点以同一 nodeId 重新注册：复活为全新生命周期（UNCONFIGURED/version=1/alive=1）。
     *
     * @return 受影响行数；0 表示节点不存在或仍存活
     */
    public int reactivateCrashedNode(String nodeId, String namespace, String nodeName) {
        return jdbcTemplate.update(
                "UPDATE ros_node SET namespace = ?, node_name = ?, lifecycle_state = 'UNCONFIGURED', "
                        + "version = 1, alive = 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE node_id = ? AND alive = 0",
                namespace, nodeName, nodeId);
    }

    public void updateNodeState(String nodeId, LifecycleState state, long newVersion) {
        jdbcTemplate.update(
                "UPDATE ros_node SET lifecycle_state = ?, version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE node_id = ?",
                state.name(), newVersion, nodeId);
    }

    public void markNodeCrashed(String nodeId) {
        jdbcTemplate.update(
                "UPDATE ros_node SET alive = 0, updated_at = CURRENT_TIMESTAMP WHERE node_id = ?",
                nodeId);
    }

    public List<NodePo> findAllNodes() {
        return jdbcTemplate.query(
                "SELECT node_id, namespace, node_name, lifecycle_state, version, alive, "
                        + "registered_at, updated_at FROM ros_node ORDER BY node_id",
                NODE_MAPPER);
    }

    // ==================== 资源 ====================

    public List<ResourcePo> findResourcesByNode(String nodeId) {
        return jdbcTemplate.query(
                "SELECT id, node_id, resource_category, fully_qualified_name, interface_type, created_at "
                        + "FROM node_resource WHERE node_id = ? ORDER BY id",
                RESOURCE_MAPPER, nodeId);
    }

    public void insertResource(String nodeId, ResourceCategory category, String fqn, String interfaceType) {
        jdbcTemplate.update(
                "INSERT INTO node_resource (node_id, resource_category, fully_qualified_name, interface_type, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                nodeId, category.name(), fqn, interfaceType);
    }

    /**
     * 统计当前存活节点中持有某互斥资源名（类别+全限定名）的不同节点数。
     */
    public int countExclusiveHolders(ResourceCategory category, String fqn) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT nr.node_id) FROM node_resource nr "
                        + "INNER JOIN ros_node n ON n.node_id = nr.node_id "
                        + "WHERE nr.resource_category = ? AND nr.fully_qualified_name = ? AND n.alive = 1",
                Integer.class, category.name(), fqn);
        return count == null ? 0 : count;
    }

    public int deleteResourcesByNode(String nodeId) {
        return jdbcTemplate.update("DELETE FROM node_resource WHERE node_id = ?", nodeId);
    }

    /**
     * 删除节点行（调用方须先删除其资源以满足外键约束）。
     */
    public void deleteNode(String nodeId) {
        jdbcTemplate.update("DELETE FROM ros_node WHERE node_id = ?", nodeId);
    }

    /**
     * 检查点恢复时插入资源行，保留原自增主键。
     */
    public void insertRestoredResource(ResourcePo resource) {
        jdbcTemplate.update(
                "INSERT INTO node_resource (id, node_id, resource_category, fully_qualified_name, "
                        + "interface_type, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                resource.id(), resource.nodeId(), resource.resourceCategory().name(),
                resource.fullyQualifiedName(), resource.interfaceType(),
                Timestamp.from(resource.createdAt()));
    }

    public List<ResourcePo> findAllResources() {
        return jdbcTemplate.query(
                "SELECT id, node_id, resource_category, fully_qualified_name, interface_type, created_at "
                        + "FROM node_resource ORDER BY id",
                RESOURCE_MAPPER);
    }

    // ==================== 事件 ====================

    /**
     * 在事务内推进单行序列并取下一个 revision，行锁保证全局连续递增。
     */
    public long nextRevision() {
        jdbcTemplate.update("UPDATE revision_seq SET next_value = next_value + 1 WHERE id = 1");
        Long value = jdbcTemplate.queryForObject(
                "SELECT next_value FROM revision_seq WHERE id = 1", Long.class);
        return value == null ? 1L : value;
    }

    public void insertEvent(EventPo event) {
        jdbcTemplate.update(
                "INSERT INTO resource_event (revision, node_id, event_type, resource_category, "
                        + "fully_qualified_name, interface_type, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                event.revision(), event.nodeId(), event.eventType(),
                event.resourceCategory() == null ? null : event.resourceCategory().name(),
                event.fullyQualifiedName(), event.interfaceType(), event.detail(),
                Timestamp.from(event.createdAt()));
    }

    public List<EventPo> findEventsAfter(long revision, int limit) {
        return jdbcTemplate.query(
                "SELECT revision, node_id, event_type, resource_category, fully_qualified_name, "
                        + "interface_type, detail, created_at FROM resource_event "
                        + "WHERE revision > ? ORDER BY revision ASC LIMIT ?",
                EVENT_MAPPER, revision, limit);
    }

    public long maxEventRevision() {
        Long value = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(revision), 0) FROM resource_event", Long.class);
        return value == null ? 0L : value;
    }

    public List<EventPo> findAllEvents() {
        return jdbcTemplate.query(
                "SELECT revision, node_id, event_type, resource_category, fully_qualified_name, "
                        + "interface_type, detail, created_at FROM resource_event ORDER BY revision",
                EVENT_MAPPER);
    }

    public long currentRevisionValue() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT next_value FROM revision_seq WHERE id = 1", Long.class);
        return value == null ? 0L : value;
    }

    public void resetRevisionValue(long value) {
        jdbcTemplate.update("UPDATE revision_seq SET next_value = ? WHERE id = 1", value);
    }

    // ==================== 幂等记录 ====================

    public Optional<IdempotentRow> findIdempotent(String requestId) {
        List<IdempotentRow> rows = jdbcTemplate.query(
                "SELECT request_id, request_kind, params_hash, node_id, http_status, response_body "
                        + "FROM idempotent_request WHERE request_id = ?",
                IDEMPOTENT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 尝试占用 requestId（http_status 为 NULL 表示处理中）。
     *
     * @return true=占位成功；false=requestId 已存在（处理中或已完成）
     */
    public boolean tryInsertInflight(String requestId, String kind, String paramsHash, String nodeId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotent_request (request_id, request_kind, params_hash, node_id, "
                            + "http_status, response_body, created_at) VALUES (?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP)",
                    requestId, kind, paramsHash, nodeId);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void completeIdempotent(String requestId, int httpStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotent_request SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }

    /**
     * 释放未完成的占位（业务抛出非预期异常时允许客户端用同 requestId 重试）。
     */
    public void deleteIdempotent(String requestId) {
        jdbcTemplate.update("DELETE FROM idempotent_request WHERE request_id = ? AND http_status IS NULL",
                requestId);
    }

    /**
     * 检查点恢复时插入一条已完成的幂等记录。
     */
    public void insertCompletedIdempotent(IdempotentRow row) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_request (request_id, request_kind, params_hash, node_id, "
                        + "http_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                row.requestId(), row.requestKind(), row.paramsHash(), row.nodeId(),
                row.httpStatus(), row.responseBody());
    }

    public List<IdempotentRow> findAllIdempotents() {
        return jdbcTemplate.query(
                "SELECT request_id, request_kind, params_hash, node_id, http_status, response_body "
                        + "FROM idempotent_request ORDER BY request_id",
                IDEMPOTENT_MAPPER);
    }

    // ==================== 检查点 ====================

    public void saveCheckpoint(String checkpointId, String payload) {
        jdbcTemplate.update(
                "INSERT INTO registry_checkpoint (checkpoint_id, payload, created_at) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP)",
                checkpointId, payload);
    }

    public Optional<String> findCheckpoint(String checkpointId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT payload FROM registry_checkpoint WHERE checkpoint_id = ?",
                (rs, n) -> rs.getString("payload"), checkpointId);
        return rows.stream().findFirst();
    }

    public boolean checkpointExists(String checkpointId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM registry_checkpoint WHERE checkpoint_id = ?",
                Integer.class, checkpointId);
        return count != null && count > 0;
    }

    // ==================== 恢复用清空 ====================

    /**
     * 清空全部业务表并将 revision 序列重置为 0（检查点表保留），须在单事务内调用。
     *
     * @param preserveRequestId 需保留的幂等占坑行（当前恢复请求自身），可为 null
     */
    public void clearBusinessTables(String preserveRequestId) {
        jdbcTemplate.update("DELETE FROM node_resource");
        jdbcTemplate.update("DELETE FROM resource_event");
        if (preserveRequestId == null) {
            jdbcTemplate.update("DELETE FROM idempotent_request");
        } else {
            jdbcTemplate.update("DELETE FROM idempotent_request WHERE request_id <> ?", preserveRequestId);
        }
        jdbcTemplate.update("DELETE FROM ros_node");
        jdbcTemplate.update("UPDATE revision_seq SET next_value = 0 WHERE id = 1");
    }

    /**
     * 幂等请求记录行。
     *
     * @param requestId    请求幂等键
     * @param requestKind  请求类别
     * @param paramsHash   参数哈希
     * @param nodeId       关联节点
     * @param httpStatus   首请求 HTTP 状态；处理中为 null
     * @param responseBody 首请求响应体（成功为视图 JSON，失败为错误 JSON）；处理中为 null
     */
    public record IdempotentRow(
            String requestId,
            String requestKind,
            String paramsHash,
            String nodeId,
            Integer httpStatus,
            String responseBody) {
    }

    // ==================== RowMappers ====================

    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static final RowMapper<NodePo> NODE_MAPPER = (rs, n) -> new NodePo(
            rs.getString("node_id"),
            rs.getString("namespace"),
            rs.getString("node_name"),
            LifecycleState.valueOf(rs.getString("lifecycle_state")),
            rs.getLong("version"),
            rs.getInt("alive") == 1,
            toInstant(rs.getTimestamp("registered_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private static final RowMapper<ResourcePo> RESOURCE_MAPPER = (rs, n) -> new ResourcePo(
            rs.getLong("id"),
            rs.getString("node_id"),
            ResourceCategory.valueOf(rs.getString("resource_category")),
            rs.getString("fully_qualified_name"),
            rs.getString("interface_type"),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<EventPo> EVENT_MAPPER = (rs, n) -> {
        String category = rs.getString("resource_category");
        return new EventPo(
                rs.getLong("revision"),
                rs.getString("node_id"),
                rs.getString("event_type"),
                category == null ? null : ResourceCategory.valueOf(category),
                rs.getString("fully_qualified_name"),
                rs.getString("interface_type"),
                rs.getString("detail"),
                toInstant(rs.getTimestamp("created_at")));
    };

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"),
            rs.getString("request_kind"),
            rs.getString("params_hash"),
            rs.getString("node_id"),
            (Integer) rs.getObject("http_status"),
            rs.getString("response_body"));
}
