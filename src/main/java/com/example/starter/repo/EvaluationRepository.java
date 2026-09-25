package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 日评估数据访问。SEALED 为终态，重复封存幂等返回首次结果。
 */
@Repository
public class EvaluationRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvaluationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Evaluation(
            Long id,
            String zoneCode,
            LocalDate evalDate,
            String status,
            int inBandCount,
            int totalCount,
            BigDecimal achievementRate,
            BigDecimal budgetKwh,
            BigDecimal consumedKwh,
            String energyDetailJson,
            Instant createdAt,
            Instant sealedAt) {
    }

    private static final RowMapper<Evaluation> MAPPER = (rs, n) -> new Evaluation(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getDate("eval_date").toLocalDate(),
            rs.getString("status"),
            rs.getInt("in_band_count"),
            rs.getInt("total_count"),
            rs.getBigDecimal("achievement_rate"),
            rs.getBigDecimal("budget_kwh"),
            rs.getBigDecimal("consumed_kwh"),
            rs.getString("energy_detail_json"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("sealed_at").toInstant());

    private static final String COLUMNS = "id, zone_code, eval_date, status, in_band_count, total_count, "
            + "achievement_rate, budget_kwh, consumed_kwh, energy_detail_json, created_at, sealed_at";

    public Optional<Evaluation> find(String zoneCode, LocalDate evalDate) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM daily_evaluation WHERE zone_code = ? AND eval_date = ?",
                    MAPPER, zoneCode, Date.valueOf(evalDate)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 封存插入；并发封存由 (zone_code, eval_date) 唯一约束保证仅一条成功。
     */
    public void insert(Evaluation e) {
        jdbcTemplate.update("INSERT INTO daily_evaluation (zone_code, eval_date, status, in_band_count, "
                        + "total_count, achievement_rate, budget_kwh, consumed_kwh, energy_detail_json, "
                        + "created_at, sealed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.zoneCode(), Date.valueOf(e.evalDate()), e.status(), e.inBandCount(), e.totalCount(),
                e.achievementRate(), e.budgetKwh(), e.consumedKwh(), e.energyDetailJson(),
                Timestamp.from(e.createdAt()), Timestamp.from(e.sealedAt()));
    }

    /**
     * 该 UTC 日是否已封存（封存日到达的读数只记迟到事件）。
     */
    public boolean isSealed(String zoneCode, LocalDate utcDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM daily_evaluation WHERE zone_code = ? AND eval_date = ? AND status = 'SEALED'",
                Integer.class, zoneCode, Date.valueOf(utcDate));
        return count != null && count > 0;
    }
}
