package com.example.starter.ros.repository;

import com.example.starter.ros.domain.LifecycleState;
import com.example.starter.ros.domain.RosNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 节点表 ros_node 数据访问。
 */
@Repository
public class NodeJdbcRepository {

    private static final NodeRowMapper ROW_MAPPER = new NodeRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public NodeJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入节点;并发重复注册时主键冲突由上层捕获 DataIntegrityViolationException。
     */
    public int insertNode(RosNode node) {
        return jdbcTemplate.update("""
                        INSERT INTO ros_node
                            (node_id, namespace, node_name, state, version, crashed, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                node.nodeId(), node.namespace(), node.nodeName(), node.state().name(),
                node.version(), node.crashed() ? 1 : 0,
                Timestamp.valueOf(node.createdAt()), Timestamp.valueOf(node.updatedAt()));
    }

    public Optional<RosNode> findById(String nodeId) {
        List<RosNode> nodes = jdbcTemplate.query(
                "SELECT node_id, namespace, node_name, state, version, crashed, created_at, updated_at"
                        + " FROM ros_node WHERE node_id = ?", ROW_MAPPER, nodeId);
        return nodes.stream().findFirst();
    }

    /**
     * 加行锁读取节点(SELECT ... FOR UPDATE),用于序列化对同一节点的
     * 注册/注销/异常退出竞争;行不存在时返回空(调用方据此判定 404)。
     */
    public Optional<RosNode> findByIdForUpdate(String nodeId) {
        List<RosNode> nodes = jdbcTemplate.query(
                "SELECT node_id, namespace, node_name, state, version, crashed, created_at, updated_at"
                        + " FROM ros_node WHERE node_id = ? FOR UPDATE", ROW_MAPPER, nodeId);
        return nodes.stream().findFirst();
    }

    public List<RosNode> findAll() {
        return jdbcTemplate.query(
                "SELECT node_id, namespace, node_name, state, version, crashed, created_at, updated_at"
                        + " FROM ros_node ORDER BY created_at, node_id", ROW_MAPPER);
    }

    /**
     * 乐观条件更新:仅当当前版本等于 expectedVersion 且节点未异常退出时,
     * 迁移状态并把版本 +1。返回受影响行数,0 表示版本冲突或节点已崩溃。
     */
    public int compareAndUpdateState(String nodeId, long expectedVersion,
                                     LifecycleState target, LocalDateTime now) {
        return jdbcTemplate.update("""
                        UPDATE ros_node
                           SET state = ?, version = version + 1, updated_at = ?
                         WHERE node_id = ? AND version = ? AND crashed = 0
                        """,
                target.name(), Timestamp.valueOf(now), nodeId, expectedVersion);
    }

    public int deleteNode(String nodeId) {
        return jdbcTemplate.update("DELETE FROM ros_node WHERE node_id = ?", nodeId);
    }

    /**
     * 标记节点异常退出(墓碑保留),版本不递增。
     */
    public int markCrashed(String nodeId, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE ros_node SET crashed = 1, updated_at = ? WHERE node_id = ?",
                Timestamp.valueOf(now), nodeId);
    }

    /**
     * 检查点恢复:整体覆盖节点状态/版本/墓碑标记。
     */
    public int restoreNode(RosNode node) {
        return jdbcTemplate.update("""
                        UPDATE ros_node
                           SET namespace = ?, node_name = ?, state = ?, version = ?,
                               crashed = 0, updated_at = ?
                         WHERE node_id = ?
                        """,
                node.namespace(), node.nodeName(), node.state().name(), node.version(),
                Timestamp.valueOf(node.updatedAt()), node.nodeId());
    }

    private static final class NodeRowMapper implements RowMapper<RosNode> {
        @Override
        public RosNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RosNode(
                    rs.getString("node_id"),
                    rs.getString("namespace"),
                    rs.getString("node_name"),
                    LifecycleState.valueOf(rs.getString("state")),
                    rs.getLong("version"),
                    rs.getInt("crashed") == 1,
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());
        }
    }
}
