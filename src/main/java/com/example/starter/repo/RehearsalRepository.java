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
 * 策略下发前预演留档数据访问。留档只增不改，同一策略多次预演按 id 递增形成版本序列。
 */
@Repository
public class RehearsalRepository {

    private final JdbcTemplate jdbcTemplate;

    public RehearsalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 预演留档持久化模型。能耗单位 kWh、速率单位 kWh/h；budget/consumed/remaining 为空表示当日未分配预算。
     */
    public record Rehearsal(
            Long id,
            String strategyCode,
            String requestId,
            String modelVersion,
            Instant effectiveTime,
            LocalDate evalDate,
            BigDecimal dayTemp,
            BigDecimal nightTemp,
            BigDecimal humiditySetpoint,
            long durationMinutes,
            BigDecimal hourlyRateKwh,
            BigDecimal estimatedKwh,
            BigDecimal budgetKwh,
            BigDecimal consumedKwh,
            BigDecimal remainingKwh,
            String handlingConflict,
            String verdict,
            Instant createdAt) {
    }

    private static final RowMapper<Rehearsal> MAPPER = (rs, n) -> new Rehearsal(
            rs.getLong("id"),
            rs.getString("strategy_code"),
            rs.getString("request_id"),
            rs.getString("model_version"),
            rs.getTimestamp("effective_time").toInstant(),
            rs.getDate("eval_date").toLocalDate(),
            rs.getBigDecimal("day_temp"),
            rs.getBigDecimal("night_temp"),
            rs.getBigDecimal("humidity_setpoint"),
            rs.getLong("duration_minutes"),
            rs.getBigDecimal("hourly_rate_kwh"),
            rs.getBigDecimal("estimated_kwh"),
            rs.getBigDecimal("budget_kwh"),
            rs.getBigDecimal("consumed_kwh"),
            rs.getBigDecimal("remaining_kwh"),
            rs.getString("handling_conflict"),
            rs.getString("verdict"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, strategy_code, request_id, model_version, effective_time, "
            + "eval_date, day_temp, night_temp, humidity_setpoint, duration_minutes, hourly_rate_kwh, "
            + "estimated_kwh, budget_kwh, consumed_kwh, remaining_kwh, handling_conflict, verdict, created_at";

    public void insert(Rehearsal r) {
        jdbcTemplate.update("INSERT INTO strategy_rehearsal (strategy_code, request_id, model_version, "
                        + "effective_time, eval_date, day_temp, night_temp, humidity_setpoint, duration_minutes, "
                        + "hourly_rate_kwh, estimated_kwh, budget_kwh, consumed_kwh, remaining_kwh, "
                        + "handling_conflict, verdict, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.strategyCode(), r.requestId(), r.modelVersion(), Timestamp.from(r.effectiveTime()),
                Date.valueOf(r.evalDate()), r.dayTemp(), r.nightTemp(), r.humiditySetpoint(),
                r.durationMinutes(), r.hourlyRateKwh(), r.estimatedKwh(), r.budgetKwh(), r.consumedKwh(),
                r.remainingKwh(), r.handlingConflict(), r.verdict(), Timestamp.from(r.createdAt()));
    }

    /**
     * 该策略最近一次预演留档（版本序列中 id 最大的一条）。
     */
    public Optional<Rehearsal> findLatestByStrategy(String strategyCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM strategy_rehearsal WHERE strategy_code = ? "
                            + "ORDER BY id DESC LIMIT 1",
                    MAPPER, strategyCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
