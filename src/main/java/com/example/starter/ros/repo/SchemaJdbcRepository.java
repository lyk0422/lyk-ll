package com.example.starter.ros.repo;

import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.model.ParamType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 参数 Schema 数据访问。 */
@Repository
public class SchemaJdbcRepository {

    private static final RowMapper<ParamSchema> MAPPER = (rs, n) -> new ParamSchema(
            rs.getString("node"),
            rs.getString("group_name"),
            rs.getString("name"),
            ParamType.valueOf(rs.getString("type")),
            rs.getString("default_value"),
            rs.getBoolean("required_flag"),
            rs.getBoolean("dynamic_flag"),
            rs.getString("dependency_expr")
    );

    private final JdbcTemplate jdbc;

    public SchemaJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ParamSchema> findAll() {
        return jdbc.query("SELECT node, group_name, name, type, default_value, required_flag, dynamic_flag, dependency_expr"
                + " FROM ros_param_schema ORDER BY node, group_name, name", MAPPER);
    }

    public List<ParamSchema> findByNode(String node) {
        return jdbc.query("SELECT node, group_name, name, type, default_value, required_flag, dynamic_flag, dependency_expr"
                + " FROM ros_param_schema WHERE node = ? ORDER BY group_name, name", MAPPER, node);
    }

    public void insert(ParamSchema schema) {
        jdbc.update("INSERT INTO ros_param_schema"
                        + " (node, group_name, name, type, default_value, required_flag, dynamic_flag, dependency_expr)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                schema.node(), schema.group(), schema.name(), schema.type().name(),
                schema.defaultValue(), schema.required(), schema.dynamic(), schema.dependencyExpression());
    }
}
