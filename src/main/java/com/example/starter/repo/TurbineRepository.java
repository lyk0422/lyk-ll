package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.Turbine;

/**
 * 机组数据访问。
 */
@Repository
public class TurbineRepository {

    private final JdbcTemplate jdbc;

    public TurbineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<Turbine> MAPPER = (rs, n) -> {
        Turbine t = new Turbine();
        t.setId(rs.getLong("id"));
        t.setFarmCode(rs.getString("farm_code"));
        t.setTurbineCode(rs.getString("turbine_code"));
        t.setRatedPowerKw(rs.getInt("rated_power_kw"));
        t.setTechnicalMinKw(rs.getInt("technical_min_kw"));
        t.setAvailablePowerKw(rs.getInt("available_power_kw"));
        t.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        t.setStatus(rs.getString("status"));
        return t;
    };

    public List<Turbine> findByFarm(String farmCode) {
        return jdbc.query(
                "SELECT * FROM wind_turbine WHERE farm_code = ? ORDER BY id",
                MAPPER, farmCode);
    }

    public Turbine findByCode(String turbineCode) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM wind_turbine WHERE turbine_code = ?",
                    MAPPER, turbineCode);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 对风场行加排他行锁（SELECT ... FOR UPDATE），串行化同风场的并发指令接收。 */
    public boolean lockFarmRow(String farmCode) {
        List<String> locked = jdbc.queryForList(
                "SELECT farm_code FROM wind_farm WHERE farm_code = ? FOR UPDATE",
                String.class, farmCode);
        return !locked.isEmpty();
    }

    public boolean farmExists(String farmCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wind_farm WHERE farm_code = ?", Integer.class, farmCode);
        return count != null && count > 0;
    }

    /** 以回报实测出力刷新机组最新可用出力（重分摊依据“最新可用出力”）。 */
    public void updateAvailablePower(long turbineId, int availablePowerKw, Instant updatedAt) {
        jdbc.update("UPDATE wind_turbine SET available_power_kw = ?, updated_at = ? WHERE id = ?",
                availablePowerKw, Timestamp.from(updatedAt), turbineId);
    }
}
