package com.example.starter.rosconfig.repo;

import com.example.starter.rosconfig.model.ParameterSchema;
import com.example.starter.rosconfig.model.ParamType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 参数 Schema 数据访问。
 */
@Repository
public class SchemaRepository {

    private final JdbcTemplate jdbc;

    public SchemaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<ParameterSchema> MAPPER = (rs, n) -> new ParameterSchema(
            rs.getLong("id"),
            rs.getString("node"),
            rs.getString("group_name"),
            rs.getString("name"),
            ParamType.valueOf(rs.getString("type")),
            rs.getBoolean("required_flag"),
            rs.getBoolean("dynamic_flag"),
            rs.getString("default_value"),
            rs.getString("depends_on")
    );

    public List<ParameterSchema> findByNode(String node) {
        return jdbc.query("SELECT * FROM param_schema WHERE node = ? ORDER BY group_name, name",
                MAPPER, node);
    }

    public List<ParameterSchema> findAll() {
        return jdbc.query("SELECT * FROM param_schema ORDER BY node, group_name, name", MAPPER);
    }
}
