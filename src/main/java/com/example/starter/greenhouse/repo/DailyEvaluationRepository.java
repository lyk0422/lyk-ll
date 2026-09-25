package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.domain.EvaluationStatus;
import com.example.starter.greenhouse.po.DailyEvaluation;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 日评估数据访问：(zone_id, budget_day) 唯一，封存状态机终态。
 */
@Repository
public class DailyEvaluationRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public DailyEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("daily_evaluation").usingGeneratedKeyColumns("id");
    }

    public DailyEvaluation insert(DailyEvaluation e) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", e.getZoneId());
        p.put("budget_day", java.sql.Date.valueOf(e.getBudgetDay()));
        p.put("status", e.getStatus().name());
        p.put("total_readings", e.getTotalReadings());
        p.put("achieved_readings", e.getAchievedReadings());
        p.put("achievement_rate", e.getAchievementRate());
        p.put("total_kwh", e.getTotalKwh());
        p.put("heating_kwh", e.getHeatingKwh());
        p.put("lighting_kwh", e.getLightingKwh());
        p.put("ventilation_count", e.getVentilationCount());
        p.put("sealed_at", e.getSealedAt() == null ? null : Timestamp.from(e.getSealedAt()));
        p.put("detail_json", e.getDetailJson());
        p.put("created_at", Timestamp.from(e.getCreatedAt()));
        Number key = insert.executeAndReturnKey(p);
        e.setId(key.longValue());
        return e;
    }

    public Optional<DailyEvaluation> findByZoneAndDay(Long zoneId, LocalDate day) {
        return jdbc.query("SELECT * FROM daily_evaluation WHERE zone_id = ? AND budget_day = ?",
                MAPPER, zoneId, java.sql.Date.valueOf(day)).stream().findFirst();
    }

    /** OPEN 评估可复算覆盖。 */
    public void updateOpen(DailyEvaluation e) {
        jdbc.update("UPDATE daily_evaluation SET status = ?, total_readings = ?, achieved_readings = ?, "
                        + "achievement_rate = ?, total_kwh = ?, heating_kwh = ?, lighting_kwh = ?, "
                        + "ventilation_count = ?, detail_json = ? WHERE id = ?",
                e.getStatus().name(), e.getTotalReadings(), e.getAchievedReadings(),
                e.getAchievementRate(), e.getTotalKwh(), e.getHeatingKwh(), e.getLightingKwh(),
                e.getVentilationCount(), e.getDetailJson(), e.getId());
    }

    /** 封存为终态（CAS：仅 OPEN→SEALED 生效，重复封存不覆盖）。 */
    public boolean sealIfOpen(Long id, java.time.Instant sealedAt) {
        int rows = jdbc.update(
                "UPDATE daily_evaluation SET status = 'SEALED', sealed_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(sealedAt), id);
        return rows == 1;
    }

    static final RowMapper<DailyEvaluation> MAPPER = (ResultSet rs, int rowNum) -> {
        DailyEvaluation e = new DailyEvaluation();
        e.setId(rs.getLong("id"));
        e.setZoneId(rs.getLong("zone_id"));
        e.setBudgetDay(rs.getDate("budget_day").toLocalDate());
        e.setStatus(EvaluationStatus.valueOf(rs.getString("status")));
        e.setTotalReadings(rs.getInt("total_readings"));
        e.setAchievedReadings(rs.getInt("achieved_readings"));
        e.setAchievementRate(rs.getBigDecimal("achievement_rate"));
        e.setTotalKwh(rs.getBigDecimal("total_kwh"));
        e.setHeatingKwh(rs.getBigDecimal("heating_kwh"));
        e.setLightingKwh(rs.getBigDecimal("lighting_kwh"));
        e.setVentilationCount(rs.getInt("ventilation_count"));
        Timestamp sealed = rs.getTimestamp("sealed_at");
        e.setSealedAt(sealed == null ? null : sealed.toInstant());
        e.setDetailJson(rs.getString("detail_json"));
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return e;
    };
}
