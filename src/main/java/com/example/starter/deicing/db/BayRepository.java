package com.example.starter.deicing.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 除冰车位数据访问。
 */
@Repository
public class BayRepository {

    private static final RowMapper<DeicingBayPo> MAPPER = (ResultSet rs, int rowNum) -> {
        DeicingBayPo po = new DeicingBayPo();
        po.setId(rs.getLong("id"));
        po.setBayCode(rs.getString("bay_code"));
        po.setBayName(rs.getString("bay_name"));
        po.setActive(rs.getBoolean("active"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public BayRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DeicingBayPo> findActive() {
        return jdbcTemplate.query(
                "SELECT id, bay_code, bay_name, active, created_at FROM deicing_bay WHERE active = 1 ORDER BY id",
                MAPPER);
    }
}
