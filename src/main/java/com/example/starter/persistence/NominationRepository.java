package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 日指定气量与违约金单价数据访问。
 */
@Repository
public class NominationRepository {

    private final JdbcTemplate jdbc;

    public NominationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<NominationPO> ROW_MAPPER = (rs, n) -> new NominationPO(
            rs.getLong("id"),
            rs.getString("station_code"),
            rs.getDate("gas_day").toLocalDate(),
            rs.getBigDecimal("nominated_energy_mj"),
            rs.getBigDecimal("penalty_unit_price"));

    /** 登记或覆盖某日指定气量（合成合同数据，以最新一次登记为准）。 */
    public void save(String stationCode, LocalDate gasDay, BigDecimal nominatedEnergyMj,
            BigDecimal penaltyUnitPrice, Instant now) {
        // H2（MySQL 兼容模式）原生 MERGE ... KEY 语法；按 (station_code, gas_day) 唯一键幂等登记
        jdbc.update("""
                MERGE INTO daily_nomination
                    (station_code, gas_day, nominated_energy_mj, penalty_unit_price, created_at)
                KEY (station_code, gas_day)
                VALUES (?, ?, ?, ?, ?)
                """, stationCode, Date.valueOf(gasDay), nominatedEnergyMj, penaltyUnitPrice,
                Timestamp.from(now));
    }

    /** 查询某日指定气量。 */
    public Optional<NominationPO> find(String stationCode, LocalDate gasDay) {
        return jdbc.query("""
                SELECT * FROM daily_nomination WHERE station_code = ? AND gas_day = ?
                """, ROW_MAPPER, stationCode, Date.valueOf(gasDay)).stream().findFirst();
    }
}
