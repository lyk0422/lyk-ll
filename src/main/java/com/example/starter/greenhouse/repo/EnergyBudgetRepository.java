package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.po.EnergyBudget;
import java.math.BigDecimal;
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
 * 分区单日能耗预算数据访问。
 */
@Repository
public class EnergyBudgetRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public EnergyBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("energy_budget").usingGeneratedKeyColumns("id");
    }

    public EnergyBudget insert(EnergyBudget b) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", b.getZoneId());
        p.put("budget_day", java.sql.Date.valueOf(b.getBudgetDay()));
        p.put("budget_kwh", b.getBudgetKwh());
        p.put("remaining_kwh", b.getRemainingKwh());
        p.put("created_at", Timestamp.from(b.getCreatedAt()));
        Number key = insert.executeAndReturnKey(p);
        b.setId(key.longValue());
        return b;
    }

    public Optional<EnergyBudget> findByZoneAndDay(Long zoneId, LocalDate day) {
        return jdbc.query("SELECT * FROM energy_budget WHERE zone_id = ? AND budget_day = ?",
                MAPPER, zoneId, java.sql.Date.valueOf(day)).stream().findFirst();
    }

    /** 行锁查询，串行化同分区同日的并发指令扣减。 */
    public Optional<EnergyBudget> findByZoneAndDayForUpdate(Long zoneId, LocalDate day) {
        return jdbc.query("SELECT * FROM energy_budget WHERE zone_id = ? AND budget_day = ? FOR UPDATE",
                MAPPER, zoneId, java.sql.Date.valueOf(day)).stream().findFirst();
    }

    /**
     * 原子条件扣减：仅当剩余额度足够时更新。并发下数据库行锁串行化，
     * 返回受影响行数 0 表示余额不足（恰用尽时耗能指令必失败，不会超预算）。
     */
    public boolean deductIfSufficient(Long zoneId, LocalDate day, BigDecimal kwh) {
        int rows = jdbc.update(
                "UPDATE energy_budget SET remaining_kwh = remaining_kwh - ? "
                        + "WHERE zone_id = ? AND budget_day = ? AND remaining_kwh >= ?",
                kwh, zoneId, java.sql.Date.valueOf(day), kwh);
        return rows == 1;
    }

    static final RowMapper<EnergyBudget> MAPPER = (ResultSet rs, int rowNum) -> {
        EnergyBudget b = new EnergyBudget();
        b.setId(rs.getLong("id"));
        b.setZoneId(rs.getLong("zone_id"));
        b.setBudgetDay(rs.getDate("budget_day").toLocalDate());
        b.setBudgetKwh(rs.getBigDecimal("budget_kwh"));
        b.setRemainingKwh(rs.getBigDecimal("remaining_kwh"));
        b.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return b;
    };
}
