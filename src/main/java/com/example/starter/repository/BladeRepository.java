package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 叶片档案数据访问。
 */
@Repository
public class BladeRepository {

    private final JdbcTemplate jdbc;

    public BladeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断叶片是否存在。
     */
    public boolean exists(long bladeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blade WHERE id = ?", Integer.class, bladeId);
        return count != null && count > 0;
    }
}
