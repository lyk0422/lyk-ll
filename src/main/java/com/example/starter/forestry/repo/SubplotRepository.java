package com.example.starter.forestry.repo;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.po.SubplotPO;

/**
 * 小班基础数据仓储。
 */
@Repository
public class SubplotRepository {

    private final JdbcTemplate jdbc;

    public SubplotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询小班，不存在返回 null。
     */
    public SubplotPO findById(String id) {
        var rows = jdbc.query("""
                        SELECT id, subplot_code, species_code, forest_category, protected_zone,
                               stand_age, mature_age, area
                          FROM forest_subplot
                         WHERE id = ?
                        """,
                (rs, n) -> new SubplotPO(
                        rs.getString("id"),
                        rs.getString("subplot_code"),
                        rs.getString("species_code"),
                        rs.getString("forest_category"),
                        rs.getBoolean("protected_zone"),
                        rs.getInt("stand_age"),
                        rs.getInt("mature_age"),
                        rs.getBigDecimal("area") == null ? BigDecimal.ZERO : rs.getBigDecimal("area")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 锁定小班行（SELECT ... FOR UPDATE）：同小班并发发证据此串行，
     * 保证同时刻只允许一张有效证。
     */
    public void lockForUpdate(String id) {
        jdbc.queryForObject("SELECT id FROM forest_subplot WHERE id = ? FOR UPDATE", String.class, id);
    }
}
