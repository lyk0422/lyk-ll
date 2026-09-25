package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 品种/地块合成主数据查询。
 */
@Repository
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 品种编码是否存在。 */
    public boolean varietyExists(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM variety WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    /** 地块编码是否存在。 */
    public boolean plotExists(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM field_plot WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }
}
