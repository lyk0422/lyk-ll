package com.example.starter.repo;

import java.sql.ResultSet;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.PlanFuel;

/**
 * 方案燃料明细访问。
 */
@Repository
public class FuelRepository {

    private static final RowMapper<PlanFuel> MAPPER = (ResultSet rs, int n) -> new PlanFuel(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("fuel_name"),
            rs.getString("fuel_category"),
            rs.getBoolean("high_cl_sulfur"),
            rs.getBigDecimal("heat_value_mjkg"),
            rs.getBigDecimal("mass_kg"),
            rs.getBigDecimal("chlorine_pct"),
            rs.getBigDecimal("sulfur_pct"));

    private final JdbcTemplate jdbc;

    public FuelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(long planId, List<com.example.starter.api.dto.FuelDto> fuels) {
        jdbc.batchUpdate("""
                INSERT INTO plan_fuel (plan_id, fuel_name, fuel_category, high_cl_sulfur,
                    heat_value_mjkg, mass_kg, chlorine_pct, sulfur_pct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, fuels, fuels.size(), (ps, fuel) -> {
                    ps.setLong(1, planId);
                    ps.setString(2, fuel.fuelName());
                    ps.setString(3, fuel.fuelCategory());
                    ps.setBoolean(4, Boolean.TRUE.equals(fuel.highClSulfur()));
                    ps.setBigDecimal(5, fuel.heatValueMjkg());
                    ps.setBigDecimal(6, fuel.massKg());
                    ps.setBigDecimal(7, fuel.chlorinePct());
                    ps.setBigDecimal(8, fuel.sulfurPct());
                });
    }

    public List<PlanFuel> findByPlanId(long planId) {
        return jdbc.query("""
                SELECT id, plan_id, fuel_name, fuel_category, high_cl_sulfur,
                    heat_value_mjkg, mass_kg, chlorine_pct, sulfur_pct
                FROM plan_fuel WHERE plan_id = ? ORDER BY id
                """, MAPPER, planId);
    }
}
