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
import java.util.List;

/**
 * 下发前预演与仲裁留档数据访问。每次下发尝试追加一行，随策略版本留存，可复算。
 */
@Repository
public class RehearsalRepository {

    private final JdbcTemplate jdbcTemplate;

    public RehearsalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 预演留档记录。能耗单位 kWh；budgetKwh/remainingKwh 为空表示当日未分配预算（预演不拦截）。
     */
    public record Rehearsal(
            Long id,
            String strategyCode,
            String requestId,
            Instant effectiveTime,
            long execMinutes,
            BigDecimal estimatedKwh,
            LocalDate budgetDate,
            BigDecimal budgetKwh,
            BigDecimal remainingKwh,
            String decision,
            String reason,
            Instant createdAt) {
    }

    private static final RowMapper<Rehearsal> MAPPER = (rs, n) -> new Rehearsal(
            rs.getLong("id"),
            rs.getString("strategy_code"),
            rs.getString("request_id"),
            rs.getTimestamp("effective_time").toInstant(),
            rs.getLong("exec_minutes"),
            rs.getBigDecimal("estimated_kwh"),
            rs.getDate("budget_date").toLocalDate(),
            rs.getBigDecimal("budget_kwh"),
            rs.getBigDecimal("remaining_kwh"),
            rs.getString("decision"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant());

    private static final String COLUMNS = "id, strategy_code, request_id, effective_time, exec_minutes, "
            + "estimated_kwh, budget_date, budget_kwh, remaining_kwh, decision, reason, created_at";

    public void insert(Rehearsal r) {
        jdbcTemplate.update("INSERT INTO strategy_rehearsal (strategy_code, request_id, effective_time, "
                        + "exec_minutes, estimated_kwh, budget_date, budget_kwh, remaining_kwh, "
                        + "decision, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.strategyCode(), r.requestId(), Timestamp.from(r.effectiveTime()),
                r.execMinutes(), r.estimatedKwh(), Date.valueOf(r.budgetDate()),
                r.budgetKwh(), r.remainingKwh(), r.decision(), r.reason(), Timestamp.from(r.createdAt()));
    }

    /**
     * 按策略编码查询全部预演留档（每次下发尝试一行），按 id 升序。
     */
    public List<Rehearsal> findByStrategyCode(String strategyCode) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM strategy_rehearsal WHERE strategy_code = ? ORDER BY id",
                MAPPER, strategyCode);
    }
}
