package com.example.starter.water.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 取水监管服务数据访问层，基于 JdbcTemplate 的参数化 SQL。
 * 并发约定：申报与限取通过对 water_user / reach_restriction 行 SELECT ... FOR UPDATE 串行化。
 */
@Repository
public class WaterRepository {

    private final JdbcTemplate jdbc;

    public WaterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.UserRow> USER_MAPPER = (rs, n) -> new Rows.UserRow(
            rs.getLong("id"), rs.getString("user_code"), rs.getString("user_name"),
            rs.getString("reach_code"), rs.getBigDecimal("annual_quota"));

    private static final RowMapper<Rows.MeterPointRow> POINT_MAPPER = (rs, n) -> new Rows.MeterPointRow(
            rs.getLong("id"), rs.getString("point_code"), rs.getLong("user_id"));

    private static final RowMapper<Rows.RestrictionRow> RESTRICTION_MAPPER = (rs, n) -> new Rows.RestrictionRow(
            rs.getLong("id"), rs.getString("reach_code"), rs.getBoolean("active"),
            rs.getTimestamp("effective_at") == null ? null : rs.getTimestamp("effective_at").toLocalDateTime());

    private static final RowMapper<Rows.LedgerRow> LEDGER_MAPPER = (rs, n) -> new Rows.LedgerRow(
            rs.getLong("id"), rs.getLong("user_id"), rs.getInt("quota_year"),
            rs.getBigDecimal("base_quota"), rs.getBigDecimal("carryover_deduction"));

    private static final RowMapper<Rows.DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new Rows.DeclarationRow(
            rs.getLong("id"), rs.getLong("user_id"), rs.getString("plan_month"), rs.getInt("version"),
            rs.getBigDecimal("planned_amount"), rs.getBigDecimal("accepted_amount"),
            rs.getBoolean("restricted"), rs.getString("request_id"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<Rows.IdempotencyRow> IDEMPOTENCY_MAPPER = (rs, n) -> {
        long declarationId = rs.getLong("declaration_id");
        return new Rows.IdempotencyRow(
                rs.getString("request_id"), rs.getLong("user_id"), rs.getString("plan_month"),
                rs.getBigDecimal("planned_amount"), rs.getString("status"),
                rs.getBigDecimal("accepted_amount"), rs.wasNull() ? null : declarationId,
                rs.getString("reason"), rs.getTimestamp("created_at").toLocalDateTime());
    };

    private static final RowMapper<Rows.ReadingRow> READING_MAPPER = (rs, n) -> new Rows.ReadingRow(
            rs.getLong("id"), rs.getString("meter_point"), rs.getLong("user_id"),
            rs.getTimestamp("read_at").toLocalDateTime(), rs.getString("bucket_month"),
            rs.getBigDecimal("cumulative_reading"), rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<Rows.SealRow> SEAL_MAPPER = (rs, n) -> new Rows.SealRow(
            rs.getLong("id"), rs.getLong("user_id"), rs.getInt("quota_year"),
            rs.getTimestamp("sealed_at").toLocalDateTime());

    // ---------- 取水户 ----------

    public long insertUser(String userCode, String userName, String reachCode, BigDecimal annualQuota) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO water_user (user_code, user_name, reach_code, annual_quota) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, userCode);
            ps.setString(2, userName);
            ps.setString(3, reachCode);
            ps.setBigDecimal(4, annualQuota);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Rows.UserRow> findUserByCode(String userCode) {
        return jdbc.query("SELECT * FROM water_user WHERE user_code = ?", USER_MAPPER, userCode)
                .stream().findFirst();
    }

    public Optional<Rows.UserRow> findUserById(long id) {
        return jdbc.query("SELECT * FROM water_user WHERE id = ?", USER_MAPPER, id)
                .stream().findFirst();
    }

    /** 申报流程串行化：按取水户行加写锁。 */
    public Optional<Rows.UserRow> lockUserByCode(String userCode) {
        return jdbc.query("SELECT * FROM water_user WHERE user_code = ? FOR UPDATE", USER_MAPPER, userCode)
                .stream().findFirst();
    }

    // ---------- 计量点 ----------

    public long insertMeterPoint(String pointCode, long userId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_point (point_code, user_id) VALUES (?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, pointCode);
            ps.setLong(2, userId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Rows.MeterPointRow> findMeterPoint(String pointCode) {
        return jdbc.query("SELECT * FROM meter_point WHERE point_code = ?", POINT_MAPPER, pointCode)
                .stream().findFirst();
    }

    // ---------- 河段限取 ----------

    /** 确保河段限取行存在（创建取水户/计量点时调用），已存在则忽略。 */
    public void ensureRestrictionRow(String reachCode) {
        try {
            jdbc.update("INSERT INTO reach_restriction (reach_code, active) VALUES (?, FALSE)", reachCode);
        } catch (DuplicateKeyException ignored) {
            // 河段行已存在，幂等忽略
        }
    }

    /** 申报与限取触发共用：按河段行加写锁读取，保证并发下唯一时序结果。 */
    public Optional<Rows.RestrictionRow> lockRestriction(String reachCode) {
        return jdbc.query("SELECT * FROM reach_restriction WHERE reach_code = ? FOR UPDATE",
                RESTRICTION_MAPPER, reachCode).stream().findFirst();
    }

    public Optional<Rows.RestrictionRow> findRestriction(String reachCode) {
        return jdbc.query("SELECT * FROM reach_restriction WHERE reach_code = ?",
                RESTRICTION_MAPPER, reachCode).stream().findFirst();
    }

    public void activateRestriction(String reachCode, LocalDateTime effectiveAt) {
        jdbc.update("UPDATE reach_restriction SET active = TRUE, effective_at = ? WHERE reach_code = ?",
                Timestamp.valueOf(effectiveAt), reachCode);
    }

    // ---------- 年度额度台账 ----------

    public Optional<Rows.LedgerRow> lockLedger(long userId, int year) {
        return jdbc.query("SELECT * FROM quota_ledger WHERE user_id = ? AND quota_year = ? FOR UPDATE",
                LEDGER_MAPPER, userId, year).stream().findFirst();
    }

    public Optional<Rows.LedgerRow> findLedger(long userId, int year) {
        return jdbc.query("SELECT * FROM quota_ledger WHERE user_id = ? AND quota_year = ?",
                LEDGER_MAPPER, userId, year).stream().findFirst();
    }

    public void insertLedger(long userId, int year, BigDecimal baseQuota, BigDecimal carryoverDeduction) {
        jdbc.update("INSERT INTO quota_ledger (user_id, quota_year, base_quota, carryover_deduction)"
                        + " VALUES (?,?,?,?)",
                userId, year, baseQuota, carryoverDeduction);
    }

    public void updateCarryover(long ledgerId, BigDecimal carryoverDeduction) {
        jdbc.update("UPDATE quota_ledger SET carryover_deduction = ? WHERE id = ?",
                carryoverDeduction, ledgerId);
    }

    // ---------- 月度申报 ----------

    public int maxDeclarationVersion(long userId, String month) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version) FROM monthly_declaration WHERE user_id = ? AND plan_month = ?",
                Integer.class, userId, month);
        return max == null ? 0 : max;
    }

    /** 该年度已确认申报的受理水量合计（每月取最新版本）。 */
    public BigDecimal sumAcceptedForYear(long userId, int year) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(d.accepted_amount), 0) FROM monthly_declaration d "
                        + "JOIN (SELECT plan_month, MAX(version) AS v FROM monthly_declaration "
                        + "      WHERE user_id = ? AND plan_month LIKE ? GROUP BY plan_month) latest "
                        + "  ON d.user_id = ? AND d.plan_month = latest.plan_month AND d.version = latest.v",
                BigDecimal.class, userId, year + "-%", userId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 指定月当前生效（最新版本）的受理水量，无则为 0。 */
    public BigDecimal currentMonthAccepted(long userId, String month) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(accepted_amount), 0) FROM monthly_declaration "
                        + "WHERE user_id = ? AND plan_month = ? AND version = "
                        + "(SELECT MAX(version) FROM monthly_declaration WHERE user_id = ? AND plan_month = ?)",
                BigDecimal.class, userId, month, userId, month);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public long insertDeclaration(long userId, String month, int version, BigDecimal plannedAmount,
                                  BigDecimal acceptedAmount, boolean restricted, String requestId,
                                  LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO monthly_declaration (user_id, plan_month, version, planned_amount,"
                            + " accepted_amount, restricted, request_id, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, month);
            ps.setInt(3, version);
            ps.setBigDecimal(4, plannedAmount);
            ps.setBigDecimal(5, acceptedAmount);
            ps.setBoolean(6, restricted);
            ps.setString(7, requestId);
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Rows.DeclarationRow> findDeclarationById(long id) {
        return jdbc.query("SELECT * FROM monthly_declaration WHERE id = ?",
                DECLARATION_MAPPER, id).stream().findFirst();
    }

    // ---------- 年度计划封存 ----------
    public Optional<Rows.SealRow> findSeal(long userId, int year) {
        return jdbc.query("SELECT * FROM annual_plan_seal WHERE user_id = ? AND quota_year = ?",
                SEAL_MAPPER, userId, year).stream().findFirst();
    }

    public void insertSeal(long userId, int year, LocalDateTime sealedAt) {
        jdbc.update("INSERT INTO annual_plan_seal (user_id, quota_year, sealed_at) VALUES (?,?,?)",
                userId, year, Timestamp.valueOf(sealedAt));
    }

    // ---------- 申报幂等 ----------

    public Optional<Rows.IdempotencyRow> findIdempotency(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                IDEMPOTENCY_MAPPER, requestId).stream().findFirst();
    }

    public void insertIdempotency(String requestId, long userId, String month, BigDecimal plannedAmount,
                                  String status, BigDecimal acceptedAmount, Long declarationId,
                                  String reason, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, user_id, plan_month, planned_amount,"
                        + " status, accepted_amount, declaration_id, reason, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                requestId, userId, month, plannedAmount, status, acceptedAmount, declarationId, reason,
                Timestamp.valueOf(createdAt));
    }

    /** 确认版本生成后回填幂等记录的申报版本ID。 */
    public void bindIdempotencyDeclaration(String requestId, long declarationId) {
        jdbc.update("UPDATE idempotency_record SET declaration_id = ? WHERE request_id = ?",
                declarationId, requestId);
    }

    // ---------- 计量读数 ----------

    public long insertReading(String meterPoint, long userId, LocalDateTime readAt, String bucketMonth,
                              BigDecimal cumulativeReading, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_reading (meter_point, user_id, read_at, bucket_month,"
                            + " cumulative_reading, created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, meterPoint);
            ps.setLong(2, userId);
            ps.setTimestamp(3, Timestamp.valueOf(readAt));
            ps.setString(4, bucketMonth);
            ps.setBigDecimal(5, cumulativeReading);
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Rows.ReadingRow> findReading(String meterPoint, LocalDateTime readAt) {
        return jdbc.query("SELECT * FROM meter_reading WHERE meter_point = ? AND read_at = ?",
                READING_MAPPER, meterPoint, Timestamp.valueOf(readAt)).stream().findFirst();
    }

    /** 取水户全部读数，按计量点+表底时刻排序，用于差分归月。 */
    public List<Rows.ReadingRow> listReadingsByUser(long userId) {
        return jdbc.query("SELECT * FROM meter_reading WHERE user_id = ?"
                + " ORDER BY meter_point, read_at", READING_MAPPER, userId);
    }
}
