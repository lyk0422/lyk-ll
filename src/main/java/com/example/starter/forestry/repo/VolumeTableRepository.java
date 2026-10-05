package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成蓄积表仓储：按树种+林龄查询单株蓄积，用于复核申请填报的蓄积设计值。
 */
@Repository
public class VolumeTableRepository {

    private final JdbcTemplate jdbc;

    public VolumeTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询该树种该林龄的单株蓄积（立方米/株），无对应档位返回 null。
     */
    public BigDecimal findVolumePerTree(String speciesCode, int standAge) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT volume_per_tree FROM species_volume_table"
                        + " WHERE species_code = ? AND stand_age = ?",
                (rs, n) -> rs.getBigDecimal("volume_per_tree"), speciesCode, standAge);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
