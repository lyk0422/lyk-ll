package com.example.starter.ros.db;

import com.example.starter.ros.ParamType;
import com.example.starter.ros.ParameterSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 参数 Schema 数据访问。
 */
@Repository
public class SchemaRepository {

    private static final RowMapper<ParameterSchema> MAPPER = (rs, n) -> new ParameterSchema(
            rs.getString("node_id"),
            rs.getString("group_name"),
            rs.getString("param_key"),
            ParamType.valueOf(rs.getString("param_type")),
            rs.getString("default_value"),
            rs.getBoolean("required_flag"),
            rs.getBoolean("dynamic_flag"),
            rs.getString("dependency_expr"));

    private final JdbcTemplate jdbcTemplate;

    public SchemaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按节点读取全部参数 Schema（按组名、参数名排序，保证校验与字段顺序无关时仍有确定顺序）。 */
    public List<ParameterSchema> findByNode(String nodeId) {
        return jdbcTemplate.query(
                "SELECT node_id, group_name, param_key, param_type, default_value, "
                        + "required_flag, dynamic_flag, dependency_expr "
                        + "FROM param_schema WHERE node_id = ? ORDER BY group_name, param_key",
                MAPPER, nodeId);
    }

    /** 幂等写入一条 Schema（同主键覆盖）。 */
    public void upsert(ParameterSchema schema) {
        jdbcTemplate.update(
                "MERGE INTO param_schema (node_id, group_name, param_key, param_type, "
                        + "default_value, required_flag, dynamic_flag, dependency_expr) "
                        + "KEY (node_id, group_name, param_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                schema.nodeId(), schema.groupName(), schema.key(), schema.type().name(),
                schema.defaultValue(), schema.required(), schema.dynamic(), schema.dependencyExpr());
    }
}
