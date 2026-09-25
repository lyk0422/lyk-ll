package com.example.starter.waterlogging.repo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.PumpStation;

/**
 * 泵站台账数据访问。
 */
@Repository
public class PumpStationRepository {

    private static final String COLUMNS =
            "id, station_code, station_name, basin_zone, safety_low_level, run_status, gate_opening";

    private static final RowMapper<PumpStation> MAPPER = (rs, n) -> new PumpStation(
            rs.getLong("id"),
            rs.getString("station_code"),
            rs.getString("station_name"),
            rs.getString("basin_zone"),
            rs.getBigDecimal("safety_low_level"),
            rs.getString("run_status"),
            rs.getBigDecimal("gate_opening"));

    public PumpStationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    /** 按泵站编码查询。 */
    public Optional<PumpStation> findByCode(String stationCode) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM pump_station WHERE station_code = ?", MAPPER, stationCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询某流域分区下全部泵站（联动范围）。 */
    public List<PumpStation> findByZone(String basinZone) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM pump_station WHERE basin_zone = ? ORDER BY id",
                MAPPER, basinZone);
    }

    /**
     * 行级锁定泵站（须在事务内），串行化同站并发指令。
     */
    public Optional<PumpStation> findByCodeForUpdate(String stationCode) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM pump_station WHERE station_code = ? FOR UPDATE",
                    MAPPER, stationCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 同事务更新机组状态与闸门开度，保证“启泵与闸门到位”原子落库。
     *
     * @return 受影响行数
     */
    public int updateState(String stationCode, String runStatus, BigDecimal gateOpening) {
        return jdbcTemplate.update("""
                UPDATE pump_station SET run_status = ?, gate_opening = ? WHERE station_code = ?
                """, runStatus, gateOpening, stationCode);
    }
}
