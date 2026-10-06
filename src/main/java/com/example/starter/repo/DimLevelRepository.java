package com.example.starter.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 档位功率字典数据访问。
 */
@Repository
public class DimLevelRepository {

    private final JdbcTemplate jdbc;

    public DimLevelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询档位功率(W)，档位不存在时返回 null。 */
    public Integer wattsFor(int level) {
        List<Integer> rows = jdbc.query("SELECT watts FROM dim_level WHERE level = ?",
                (rs, rowNum) -> rs.getInt("watts"), level);
        return rows.stream().findFirst().orElse(null);
    }
}
