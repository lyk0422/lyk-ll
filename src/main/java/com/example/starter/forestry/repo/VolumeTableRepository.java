package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成蓄积表仓储：按树种提供单株蓄积，用于复核申请所附小班蓄积设计值。
 */
@Repository
public class VolumeTableRepository {

    private final JdbcTemplate jdbc;

    public VolumeTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询树种单株蓄积（立方米/株），不存在返回 null。
     */
    public BigDecimal findUnitVolume(String speciesCode) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT unit_volume FROM species_volume_table WHERE species_code = ?",
                (rs, n) -> rs.getBigDecimal("unit_volume"), speciesCode);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
