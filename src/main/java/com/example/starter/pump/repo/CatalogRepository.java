package com.example.starter.pump.repo;

import com.example.starter.pump.domain.AlertLevel;
import com.example.starter.pump.domain.PumpStation;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合成基础数据仓储：预警级别、流域分区、泵站档案。
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按代码查询预警级别，不存在返回空。 */
    public Optional<AlertLevel> findAlertLevel(String code) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT code, name, rank_no, trigger_pumpdown FROM alert_level WHERE code = ?",
                    (rs, n) -> new AlertLevel(
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getInt("rank_no"),
                            rs.getInt("trigger_pumpdown") == 1),
                    code));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 分区是否存在。 */
    public boolean zoneExists(String zoneCode) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(1) FROM basin_zone WHERE code = ?",
                Integer.class, zoneCode);
        return cnt != null && cnt > 0;
    }

    /** 按代码查询泵站档案，不存在返回空。 */
    public Optional<PumpStation> findStation(String stationCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT code, name, zone_code, target_level, safe_low_level, gate_start_opening
                    FROM pump_station WHERE code = ?
                    """,
                    (rs, n) -> new PumpStation(
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("zone_code"),
                            rs.getBigDecimal("target_level"),
                            rs.getBigDecimal("safe_low_level"),
                            rs.getBigDecimal("gate_start_opening")),
                    stationCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 分区下全部泵站。 */
    public List<PumpStation> stationsOfZone(String zoneCode) {
        return jdbc.query(
                """
                SELECT code, name, zone_code, target_level, safe_low_level, gate_start_opening
                FROM pump_station WHERE zone_code = ? ORDER BY code
                """,
                (rs, n) -> new PumpStation(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("zone_code"),
                        rs.getBigDecimal("target_level"),
                        rs.getBigDecimal("safe_low_level"),
                        rs.getBigDecimal("gate_start_opening")),
                zoneCode);
    }
}
