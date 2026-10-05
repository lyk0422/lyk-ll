package com.example.starter.fishery.db;

import com.example.starter.fishery.db.support.GeneratedKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 年度结转台账与结转核销明细访问；结转额度次年单列并优先核销。
 */
@Repository
public class CarryoverRepository {

    private static final String COLUMNS = "id, vessel_code, species_code, quota_year, source_year, "
            + "carried_kg, used_kg, created_at";

    private static final RowMapper<CarryoverRow> MAPPER = (rs, n) -> new CarryoverRow(
            rs.getLong("id"),
            rs.getString("vessel_code"),
            rs.getString("species_code"),
            rs.getInt("quota_year"),
            rs.getInt("source_year"),
            rs.getBigDecimal("carried_kg"),
            rs.getBigDecimal("used_kg"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public CarryoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入结转台账（目标年度唯一，重复结转由唯一约束拒绝）。 */
    public long insert(String vesselCode, String speciesCode, int quotaYear, int sourceYear,
                       BigDecimal carriedKg, Instant createdAt) {
        return GeneratedKeys.insertAndReturnKey(jdbc,
                "INSERT INTO quota_carryover(vessel_code, species_code, quota_year, source_year, "
                        + "carried_kg, used_kg, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)",
                vesselCode, speciesCode, quotaYear, sourceYear, carriedKg,
                Timestamp.from(createdAt)).longValue();
    }

    /** 查询某船某鱼种目标年度的结转台账（不存在返回空）。 */
    public Optional<CarryoverRow> find(String vesselCode, String speciesCode, int quotaYear) {
        List<CarryoverRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_carryover "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                MAPPER, vesselCode, speciesCode, quotaYear);
        return rows.stream().findFirst();
    }

    /** 事务内行锁读取结转台账；核销优先扣减时串行化并发。 */
    public Optional<CarryoverRow> findForUpdate(String vesselCode, String speciesCode, int quotaYear) {
        List<CarryoverRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_carryover "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ? FOR UPDATE",
                MAPPER, vesselCode, speciesCode, quotaYear);
        return rows.stream().findFirst();
    }

    /** 查询渔船目标年度全部结转台账（次年台账单列）。 */
    public List<CarryoverRow> findByVesselYear(String vesselCode, int quotaYear) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM quota_carryover "
                        + "WHERE vessel_code = ? AND quota_year = ? ORDER BY species_code",
                MAPPER, vesselCode, quotaYear);
    }

    /** 在已持有的结转行锁上扣减优先核销额度；调用方须先校验余额充足。 */
    public void deductLocked(CarryoverRow row, BigDecimal amount) {
        int updated = jdbc.update(
                "UPDATE quota_carryover SET used_kg = used_kg + ? WHERE id = ? AND used_kg = ?",
                amount, row.id(), row.usedKg());
        if (updated != 1) {
            throw new IllegalStateException("结转台账并发冲突: " + row.vesselCode()
                    + "/" + row.speciesCode() + "/" + row.quotaYear());
        }
    }

    /** 写入结转额度核销明细。 */
    public void insertSettlement(String tripCode, String vesselCode, int quotaYear,
                                 String speciesCode, BigDecimal deductedKg, Instant createdAt) {
        jdbc.update("INSERT INTO carryover_settlement(trip_code, vessel_code, quota_year, species_code, "
                + "deducted_kg, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                tripCode, vesselCode, quotaYear, speciesCode, deductedKg, Timestamp.from(createdAt));
    }

    /** 某船某鱼种某年度结转核销明细合计（台账复算用）。 */
    public BigDecimal sumSettled(String vesselCode, String speciesCode, int quotaYear) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(deducted_kg), 0) FROM carryover_settlement "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                BigDecimal.class, vesselCode, speciesCode, quotaYear);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
