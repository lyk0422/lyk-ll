package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 环控指令数据访问，能耗单位 kWh。
 */
@Repository
public class CommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public CommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Command(
            Long id,
            String zoneCode,
            String commandType,
            BigDecimal energyKwh,
            LocalDate budgetDate,
            String requestId,
            Instant issuedAt) {
    }

    private static final RowMapper<Command> MAPPER = (rs, n) -> new Command(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getString("command_type"),
            rs.getBigDecimal("energy_kwh"),
            rs.getDate("budget_date").toLocalDate(),
            rs.getString("request_id"),
            rs.getTimestamp("issued_at").toInstant());

    private static final String COLUMNS = "id, zone_code, command_type, energy_kwh, budget_date, request_id, issued_at";

    public long insert(String zoneCode, String commandType, BigDecimal energyKwh,
                       LocalDate budgetDate, String requestId, Instant issuedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO control_command (zone_code, command_type, energy_kwh, budget_date, "
                            + "request_id, issued_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, zoneCode);
            ps.setString(2, commandType);
            ps.setBigDecimal(3, energyKwh);
            ps.setDate(4, Date.valueOf(budgetDate));
            ps.setString(5, requestId);
            ps.setTimestamp(6, Timestamp.from(issuedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public boolean existsByRequestId(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM control_command WHERE request_id = ?", Integer.class, requestId);
        return count != null && count > 0;
    }

    public Command findByRequestId(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + COLUMNS + " FROM control_command WHERE request_id = ?", MAPPER, requestId);
    }

    public List<Command> findByUtcDate(String zoneCode, LocalDate utcDate) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM control_command "
                        + "WHERE zone_code = ? AND budget_date = ? ORDER BY id",
                MAPPER, zoneCode, Date.valueOf(utcDate));
    }
}
