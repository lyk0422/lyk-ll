package com.example.starter.ros.repository;

import com.example.starter.ros.domain.NodeResource;
import com.example.starter.ros.domain.ResourceCategory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 节点资源表 node_resource 与互斥占位表 service_claim 数据访问。
 * service_claim 上的主键约束(category, fq_name)在数据库层面保证
 * 两个存活节点不能声明同名 SERVICE/ACTION。
 */
@Repository
public class ResourceJdbcRepository {

    private static final ResourceRowMapper ROW_MAPPER = new ResourceRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public ResourceJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertResource(NodeResource resource) {
        jdbcTemplate.update("""
                        INSERT INTO node_resource
                            (node_id, category, fq_name, interface_type, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                resource.nodeId(), resource.category().name(), resource.fqName(),
                resource.interfaceType(), Timestamp.valueOf(resource.createdAt()));
    }

    public List<NodeResource> findByNodeId(String nodeId) {
        return jdbcTemplate.query("""
                        SELECT id, node_id, category, fq_name, interface_type, created_at
                          FROM node_resource WHERE node_id = ?
                         ORDER BY category, fq_name
                        """, ROW_MAPPER, nodeId);
    }

    public void deleteByNodeId(String nodeId) {
        jdbcTemplate.update("DELETE FROM node_resource WHERE node_id = ?", nodeId);
    }

    /**
     * 尝试占用互斥名称;被其他存活节点持有时由主键约束抛出
     * DuplicateKeyException,由上层转换为 409 RESOURCE_CONFLICT。
     */
    public void tryClaim(ResourceCategory category, String fqName, String nodeId) {
        jdbcTemplate.update(
                "INSERT INTO service_claim (category, fq_name, node_id) VALUES (?, ?, ?)",
                category.name(), fqName, nodeId);
    }

    public Optional<String> findClaimHolder(ResourceCategory category, String fqName) {
        List<String> holders = jdbcTemplate.query(
                "SELECT node_id FROM service_claim WHERE category = ? AND fq_name = ?",
                (rs, rowNum) -> rs.getString(1), category.name(), fqName);
        return holders.stream().findFirst();
    }

    public void releaseClaimsByNode(String nodeId) {
        jdbcTemplate.update("DELETE FROM service_claim WHERE node_id = ?", nodeId);
    }

    /**
     * 检查点恢复后重新占用节点全部互斥资源名称。
     */
    public void claim(ResourceCategory category, String fqName, String nodeId) {
        tryClaim(category, fqName, nodeId);
    }

    private static final class ResourceRowMapper implements RowMapper<NodeResource> {
        @Override
        public NodeResource mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new NodeResource(
                    rs.getLong("id"),
                    rs.getString("node_id"),
                    ResourceCategory.valueOf(rs.getString("category")),
                    rs.getString("fq_name"),
                    rs.getString("interface_type"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    }
}
