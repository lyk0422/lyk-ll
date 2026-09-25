package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 每日（UTC 日界）分区能耗预算数据访问，单位 kWh。
 */
@Repository
public class BudgetRepository {

    private final JdbcTemplate jdbcTemplate;

    public BudgetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Budget(
            Long id,
            String zoneCode,
            LocalDate budgetDate,
            BigDecimal budgetKwh,
            BigDecimal consumedKwh) {

        public BigDecimal remaining() {
            return budgetKwh.subtract(consumedKwh);
        }
    }

    private static final RowMapper<Budget> MAPPER = (rs, n) -> new Budget(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getDate("budget_date").toLocalDate(),
            rs.getBigDecimal("budget_kwh"),
            rs.getBigDecimal("consumed_kwh"));

    public void insert(String zoneCode, LocalDate budgetDate, BigDecimal budgetKwh) {
        jdbcTemplate.update("INSERT INTO energy_budget (zone_code, budget_date, budget_kwh, consumed_kwh) "
                        + "VALUES (?, ?, ?, 0)",
                zoneCode, Date.valueOf(budgetDate), budgetKwh);
    }

    public Optional<Budget> find(String zoneCode, LocalDate budgetDate) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT id, zone_code, budget_date, budget_kwh, consumed_kwh "
                            + "FROM energy_budget WHERE zone_code = ? AND budget_date = ?",
                    MAPPER, zoneCode, Date.valueOf(budgetDate)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 在事务内对当日预算行加行锁后读取，串行化同分区同日的预算扣减，杜绝并发超预算。
     */
    public Budget findForUpdate(String zoneCode, LocalDate budgetDate) {
        return jdbcTemplate.queryForObject(
                "SELECT id, zone_code, budget_date, budget_kwh, consumed_kwh "
                        + "FROM energy_budget WHERE zone_code = ? AND budget_date = ? FOR UPDATE",
                MAPPER, zoneCode, Date.valueOf(budgetDate));
    }

    public void addConsumed(Long id, BigDecimal energyKwh) {
        jdbcTemplate.update("UPDATE energy_budget SET consumed_kwh = consumed_kwh + ? WHERE id = ?",
                energyKwh, id);
    }
}
