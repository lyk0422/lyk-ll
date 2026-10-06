package com.example.starter.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合成保护表数据访问：按频率给出功率限值（单位 W）。
 */
@Repository
public class ProtectionLimitRepository {

    private final JdbcTemplate jdbc;

    public ProtectionLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> findLimitByFrequency(BigDecimal frequencyMhz) {
        List<Integer> rows = jdbc.query(
                "SELECT max_power_watts FROM protection_limit WHERE frequency_mhz = ?",
                (rs, n) -> rs.getInt(1),
                frequencyMhz);
        return rows.stream().findFirst();
    }
}
