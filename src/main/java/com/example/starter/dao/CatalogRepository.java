package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成基础数据访问：潜水员名册与免减压极限表。
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 潜水员工号是否存在于合成名册。 */
    public boolean diverExists(String diverCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM diver WHERE diver_code = ?",
                Integer.class, diverCode);
        return count != null && count > 0;
    }

    /** 按表定深度查免减压极限（分钟）；表中无该深度返回 null。 */
    public Integer findNdlMinutes(int depthM) {
        return jdbc.query("SELECT ndl_minutes FROM deco_table_entry WHERE depth_m = ?",
                rs -> rs.next() ? rs.getInt(1) : null, depthM);
    }
}
