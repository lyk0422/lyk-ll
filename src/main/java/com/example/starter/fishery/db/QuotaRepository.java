package com.example.starter.fishery.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 配额台账与核销明细访问；所有扣减在调用方事务内完成。
 */
@Repository
public class QuotaRepository {

    private static final String QUOTA_COLUMNS =
            "vessel_code, species_code, quota_year, quota_kg, used_kg, version";

    private static final RowMapper<QuotaRow> QUOTA_MAPPER = (rs, n) -> new QuotaRow(
            rs.getString("vessel_code"),
            rs.getString("species_code"),
            rs.getInt("quota_year"),
            rs.getBigDecimal("quota_kg"),
            rs.getBigDecimal("used_kg"),
            rs.getInt("version"));

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"),
            rs.getString("trip_code"),
            rs.getString("vessel_code"),
            rs.getInt("quota_year"),
            rs.getString("species_code"),
            rs.getBigDecimal("deducted_kg"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询单条年度配额（不存在返回空）。 */
    public Optional<QuotaRow> find(String vesselCode, String speciesCode, int year) {
        List<QuotaRow> rows = jdbc.query(
                "SELECT " + QUOTA_COLUMNS + " FROM quota "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                QUOTA_MAPPER, vesselCode, speciesCode, year);
        return rows.stream().findFirst();
    }

    /** 查询渔船某年度全部配额台账（可复算）。 */
    public List<QuotaRow> findByVesselYear(String vesselCode, int year) {
        return jdbc.query(
                "SELECT " + QUOTA_COLUMNS + " FROM quota "
                        + "WHERE vessel_code = ? AND quota_year = ? ORDER BY species_code",
                QUOTA_MAPPER, vesselCode, year);
    }

    /** 查询渔船全部年度配额台账。 */
    public List<QuotaRow> findByVessel(String vesselCode) {
        return jdbc.query(
                "SELECT " + QUOTA_COLUMNS + " FROM quota "
                        + "WHERE vessel_code = ? ORDER BY quota_year, species_code",
                QUOTA_MAPPER, vesselCode);
    }

    /**
     * 事务内行锁读取年度配额；并发核销时串行化同一（船，鱼种，年度）扣减。
     * 不存在返回空（该鱼该年度无核定配额即按 0 处理）。
     */
    public Optional<QuotaRow> findForUpdate(String vesselCode, String speciesCode, int year) {
        List<QuotaRow> rows = jdbc.query(
                "SELECT " + QUOTA_COLUMNS + " FROM quota "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ? FOR UPDATE",
                QUOTA_MAPPER, vesselCode, speciesCode, year);
        return rows.stream().findFirst();
    }

    /** 新增年度配额（合成数据维护用）。 */
    public void insert(String vesselCode, String speciesCode, int year, BigDecimal quotaKg) {
        jdbc.update("INSERT INTO quota(vessel_code, species_code, quota_year, quota_kg, used_kg, version) "
                + "VALUES (?, ?, ?, ?, 0, 0)", vesselCode, speciesCode, year, quotaKg);
    }

    /** 在已持有的配额行锁上直接扣减；调用方须先校验扣减后不超配额。 */
    public void deductLocked(QuotaRow quota, BigDecimal amount) {
        int updated = jdbc.update(
                "UPDATE quota SET used_kg = used_kg + ?, version = version + 1 "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ? AND version = ?",
                amount, quota.vesselCode(), quota.speciesCode(), quota.year(), quota.version());
        if (updated != 1) {
            throw new IllegalStateException("配额台账乐观锁冲突: " + quota.vesselCode()
                    + "/" + quota.speciesCode() + "/" + quota.year());
        }
    }

    /** 写入核销明细。 */
    public void insertSettlement(String tripCode, String vesselCode, int year,
                                 String speciesCode, BigDecimal deductedKg, Instant createdAt) {
        jdbc.update("INSERT INTO settlement(trip_code, vessel_code, quota_year, species_code, "
                + "deducted_kg, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                tripCode, vesselCode, year, speciesCode, deductedKg, Timestamp.from(createdAt));
    }

    /** 查询航次核销明细。 */
    public List<SettlementRow> findSettlements(String tripCode) {
        return jdbc.query(
                "SELECT id, trip_code, vessel_code, quota_year, species_code, deducted_kg, created_at "
                        + "FROM settlement WHERE trip_code = ? ORDER BY species_code",
                SETTLEMENT_MAPPER, tripCode);
    }

    /** 某船某鱼种某年度全部已结案核销明细合计（台账复算用）。 */
    public BigDecimal sumSettled(String vesselCode, String speciesCode, int year) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(deducted_kg), 0) FROM settlement "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                BigDecimal.class, vesselCode, speciesCode, year);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 在已持有的配额行锁上调整核定配额（正数为调入/初始化，负数为调出）；
     * 调用方须先校验调出后剩余额度不为负。
     */
    public void adjustQuotaKgLocked(QuotaRow quota, BigDecimal deltaKg) {
        int updated = jdbc.update(
                "UPDATE quota SET quota_kg = quota_kg + ?, version = version + 1 "
                        + "WHERE vessel_code = ? AND species_code = ? AND quota_year = ? AND version = ?",
                deltaKg, quota.vesselCode(), quota.speciesCode(), quota.year(), quota.version());
        if (updated != 1) {
            throw new IllegalStateException("配额台账乐观锁冲突: " + quota.vesselCode()
                    + "/" + quota.speciesCode() + "/" + quota.year());
        }
    }

    /** 次年额度初始化：不存在则插入 0 基数配额行（结转额度在结转台账单列），已存在则不变。 */
    public void insertIfAbsent(String vesselCode, String speciesCode, int year) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                Integer.class, vesselCode, speciesCode, year);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO quota(vessel_code, species_code, quota_year, quota_kg, used_kg, version) "
                    + "VALUES (?, ?, ?, 0, 0, 0)", vesselCode, speciesCode, year);
        }
    }

    /** 记录产地预警（同船同鱼种同年度只记一次）。 */
    public void insertOriginAlertIfAbsent(String vesselCode, String speciesCode, int year,
                                          BigDecimal usagePct, Instant triggeredAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM origin_alert WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                Integer.class, vesselCode, speciesCode, year);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO origin_alert(vessel_code, species_code, quota_year, usage_pct, triggered_at) "
                    + "VALUES (?, ?, ?, ?, ?)", vesselCode, speciesCode, year, usagePct,
                    Timestamp.from(triggeredAt));
        }
    }

    /** 是否已触发产地预警（已触发的额度不得结转）。 */
    public boolean originAlertExists(String vesselCode, String speciesCode, int year) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM origin_alert WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                Integer.class, vesselCode, speciesCode, year);
        return count != null && count > 0;
    }
}
