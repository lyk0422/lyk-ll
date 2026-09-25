package com.example.starter.bonded.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 保税仓数据访问。
 */
@Repository
public class WarehouseRepository {

    private final JdbcTemplate jdbc;

    public WarehouseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 保税仓是否存在。
     */
    public boolean exists(String warehouseId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouse WHERE id = ?", Integer.class, warehouseId);
        return cnt != null && cnt > 0;
    }
}
