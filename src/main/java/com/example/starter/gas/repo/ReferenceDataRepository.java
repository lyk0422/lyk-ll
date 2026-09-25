package com.example.starter.gas.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计量站、管输合同、日指定气量等基础数据访问。
 */
@Repository
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 交接站是否存在。 */
    public boolean stationExists(String stationCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM meter_station WHERE station_code = ?",
                Integer.class, stationCode);
        return count != null && count > 0;
    }

    /** 查询合同约定的违约金单价（元/能量单位）；无合同返回 null。 */
    public BigDecimal findPenaltyUnitPrice(String stationCode) {
        try {
            return jdbc.queryForObject(
                    "SELECT penalty_unit_price FROM pipeline_contract WHERE station_code = ? "
                            + "ORDER BY id LIMIT 1",
                    BigDecimal.class, stationCode);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询日指定能量；无指定记录返回 null。 */
    public BigDecimal findNomination(String stationCode, LocalDate gasDate) {
        try {
            return jdbc.queryForObject(
                    "SELECT nominated_energy FROM daily_nomination "
                            + "WHERE station_code = ? AND gas_date = ?",
                    BigDecimal.class, stationCode, java.sql.Date.valueOf(gasDate));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }
}
