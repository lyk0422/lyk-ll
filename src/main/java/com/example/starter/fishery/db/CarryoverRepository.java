package com.example.starter.fishery.db;

import com.example.starter.fishery.db.support.GeneratedKeys;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 年度结转台账访问；结转额度在次年核销时优先扣减。
 */
@Repository
public class CarryoverRepository {

    private static final String COLUMNS = "id, vessel_code, species_code, from_year, to_year, "
            + "amount_kg, used_kg, created_at";

    private static final org.springframework.jdbc.core.RowMapper<CarryoverRow> MAPPER = (rs, n) -> new CarryoverRow(
            rs.getLong("id"),
            rs.getString("vessel_code"),
            rs.getString("species_code"),
            rs.getInt("from_year"),
            rs.getInt("to_year"),
            rs.getBigDecimal("amount_kg"),
            rs.getBigDecimal("used_kg"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public CarryoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入结转台账；唯一约束（船＋鱼种＋来源年度）保证同一年度只结转一次。
     * 返回 false 表示该额度已结转过。
     */
    public boolean insert(String vesselCode, String speciesCode, int fromYear, int toYear,
                          BigDecimal amountKg, Instant createdAt) {
        try {
            GeneratedKeys.insertAndReturnKey(
                    jdbc,
                    "INSERT INTO quota_carryover(vessel_code, species_code, from_year, to_year, "
                            + "amount_kg, used_kg, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)",
                    vesselCode, speciesCode, fromYear, toYear, amountKg, Timestamp.from(createdAt));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 查询某船某鱼种某来源年度的结转台账（不存在返回空）。 */
    public Optional<CarryoverRow> find(String vesselCode, String speciesCode, int fromYear) {
        List<CarryoverRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_carryover "
                        + "WHERE vessel_code = ? AND species_code = ? AND from_year = ?",
                MAPPER, vesselCode, speciesCode, fromYear);
        return rows.stream().findFirst();
    }

    /** 查询某船某鱼种在某核销年度可用的结转台账（to_year = 核销年度）。 */
    public List<CarryoverRow> findUsableForYear(String vesselCode, String speciesCode, int settlementYear) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_carryover "
                        + "WHERE vessel_code = ? AND species_code = ? AND to_year = ?",
                MAPPER, vesselCode, speciesCode, settlementYear);
    }

    /** 事务内行锁读取结转台账；核销优先扣减时串行化。 */
    public Optional<CarryoverRow> findByIdForUpdate(Long id) {
        List<CarryoverRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_carryover WHERE id = ? FOR UPDATE",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 在已持有的结转行锁上扣减结转额度；调用方须先校验扣减后不超结转额度。 */
    public void deductLocked(CarryoverRow row, BigDecimal amount) {
        int updated = jdbc.update(
                "UPDATE quota_carryover SET used_kg = used_kg + ? WHERE id = ? AND used_kg = ?",
                amount, row.id(), row.usedKg());
        if (updated != 1) {
            throw new IllegalStateException("结转台账乐观锁冲突: " + row.id());
        }
    }

    /** 某船某鱼种某核销年度结转额度合计（台账视图用）。 */
    public BigDecimal sumAmountForYear(String vesselCode, String speciesCode, int settlementYear) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_kg), 0) FROM quota_carryover "
                        + "WHERE vessel_code = ? AND species_code = ? AND to_year = ?",
                BigDecimal.class, vesselCode, speciesCode, settlementYear);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 某船某鱼种某核销年度结转已核销合计（台账视图用）。 */
    public BigDecimal sumUsedForYear(String vesselCode, String speciesCode, int settlementYear) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(used_kg), 0) FROM quota_carryover "
                        + "WHERE vessel_code = ? AND species_code = ? AND to_year = ?",
                BigDecimal.class, vesselCode, speciesCode, settlementYear);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
