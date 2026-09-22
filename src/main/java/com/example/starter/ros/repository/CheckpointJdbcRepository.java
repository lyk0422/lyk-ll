package com.example.starter.ros.repository;

import com.example.starter.ros.domain.LifecycleState;
import com.example.starter.ros.domain.NodeCheckpoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 节点检查点表 node_checkpoint 数据访问,仅供当前 JVM 内恢复。
 */
@Repository
public class CheckpointJdbcRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> EMPTY = List.of();

    private final JdbcTemplate jdbcTemplate;

    public CheckpointJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(NodeCheckpoint checkpoint) {
        jdbcTemplate.update("""
                        MERGE INTO node_checkpoint
                            (node_id, state, version, resources_json, processed_reqids, saved_at)
                        KEY(node_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                checkpoint.nodeId(), checkpoint.state().name(), checkpoint.version(),
                checkpoint.resourcesJson(), writeIds(checkpoint.processedRequestIds()),
                Timestamp.valueOf(checkpoint.savedAt()));
    }

    public java.util.Optional<NodeCheckpoint> findById(String nodeId) {
        List<NodeCheckpoint> checkpoints = jdbcTemplate.query("""
                        SELECT node_id, state, version, resources_json, processed_reqids, saved_at
                          FROM node_checkpoint WHERE node_id = ?
                        """, new CheckpointRowMapper(), nodeId);
        return checkpoints.stream().findFirst();
    }

    public void delete(String nodeId) {
        jdbcTemplate.update("DELETE FROM node_checkpoint WHERE node_id = ?", nodeId);
    }

    private static String writeIds(List<String> ids) {
        try {
            return OBJECT_MAPPER.writeValueAsString(ids == null ? EMPTY : ids);
        } catch (Exception e) {
            throw new IllegalStateException("序列化已处理requestId失败", e);
        }
    }

    private static final class CheckpointRowMapper implements RowMapper<NodeCheckpoint> {
        @Override
        public NodeCheckpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            String json = rs.getString("processed_reqids");
            List<String> ids;
            try {
                ids = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
                });
            } catch (Exception e) {
                throw new SQLException("解析已处理requestId失败", e);
            }
            return new NodeCheckpoint(
                    rs.getString("node_id"),
                    LifecycleState.valueOf(rs.getString("state")),
                    rs.getLong("version"),
                    rs.getString("resources_json"),
                    ids,
                    rs.getTimestamp("saved_at").toLocalDateTime());
        }
    }
}
