package com.example.starter.carbon.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 碳台账数据访问：企业、履约年度核定信息、企业年度串行锁、配额/抵消批次查询与扣减。
 * 所有数量单位为吨 CO2e，时刻按 UTC 墙上时间存入 TIMESTAMP 列。
 */
@Repository
public class CarbonLedgerRepository {

    /** 配额账户类型。 */
    public static final String TYPE_ALLOWANCE = "ALLOWANCE";
    /** 抵消信用账户类型。 */
    public static final String TYPE_OFFSET = "OFFSET";

    private final JdbcTemplate jdbc;

    public CarbonLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 企业行视图。 */
    public record EnterpriseRow(long id, String code, String name) {
    }

    /** 履约年度核定信息行视图。 */
    public record AssessmentRow(
            long id, long enterpriseId, int complianceYear,
            BigDecimal verifiedEmission, Instant deadlineUtc, String status) {
    }

    /** 可用批次行视图。 */
    public record BatchRow(
            long id, String batchNo, BigDecimal totalAmount, BigDecimal remainingAmount,
            LocalDate issuedDate, LocalDate expiryDate, String status) {
    }

    private static final RowMapper<EnterpriseRow> ENTERPRISE_MAPPER = (rs, n) -> new EnterpriseRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<AssessmentRow> ASSESSMENT_MAPPER = (rs, n) -> new AssessmentRow(
            rs.getLong("id"),
            rs.getLong("enterprise_id"),
            rs.getInt("compliance_year"),
            rs.getBigDecimal("verified_emission"),
            rs.getTimestamp("deadline_utc").toInstant(),
            rs.getString("status"));

    private static final RowMapper<BatchRow> BATCH_MAPPER = new BatchRowMapper();

    public EnterpriseRow findEnterpriseByCode(String code) {
        List<EnterpriseRow> rows = jdbc.query(
                "SELECT id, code, name FROM enterprise WHERE code = ?", ENTERPRISE_MAPPER, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按ID查询企业（凭证展示用）。 */
    public EnterpriseRow findEnterpriseById(long id) {
        List<EnterpriseRow> rows = jdbc.query(
                "SELECT id, code, name FROM enterprise WHERE id = ?", ENTERPRISE_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新建合成企业，返回自增ID。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long insertEnterprise(String code, String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO enterprise (code, name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入企业未获得自增主键: " + code);
        }
        return key.longValue();
    }

    /** 新建合成履约年度核定信息。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertAssessment(long enterpriseId, int complianceYear,
                                 BigDecimal verifiedEmission, Instant deadlineUtc) {
        jdbc.update("INSERT INTO emission_assessment (enterprise_id, compliance_year, verified_emission, "
                        + "deadline_utc, status) VALUES (?, ?, ?, ?, 'PENDING')",
                enterpriseId, complianceYear, verifiedEmission, Timestamp.from(deadlineUtc));
    }

    /** 新建合成配额批次或抵消信用批次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertBatch(String accountType, long enterpriseId, String batchNo,
                            BigDecimal totalAmount, LocalDate issuedDate, LocalDate expiryDate) {
        String sql;
        if (TYPE_ALLOWANCE.equals(accountType)) {
            sql = "INSERT INTO allowance_batch (enterprise_id, batch_no, total_amount, remaining_amount, "
                    + "issued_date, expiry_date, status) VALUES (?,?,?,?,?,?,'ACTIVE')";
        } else {
            sql = "INSERT INTO offset_credit (enterprise_id, credit_no, total_amount, remaining_amount, "
                    + "issued_date, expiry_date, status) VALUES (?,?,?,?,?,?,'ACTIVE')";
        }
        jdbc.update(sql, enterpriseId, batchNo, totalAmount, totalAmount,
                java.sql.Date.valueOf(issuedDate), java.sql.Date.valueOf(expiryDate));
    }

    /**
     * 查询履约年度核定信息；forUpdate=true 时加行锁，须在事务内调用。
     */
    public AssessmentRow findAssessment(long enterpriseId, int complianceYear, boolean forUpdate) {
        String sql = "SELECT id, enterprise_id, compliance_year, verified_emission, deadline_utc, status "
                + "FROM emission_assessment WHERE enterprise_id = ? AND compliance_year = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        List<AssessmentRow> rows = jdbc.query(sql, ASSESSMENT_MAPPER, enterpriseId, complianceYear);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 确保企业+履约年度串行锁行存在（MERGE 幂等），随后须调用 {@link #lockComplianceYear} 加锁。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureComplianceLock(long enterpriseId, int complianceYear) {
        jdbc.update("MERGE INTO compliance_lock (enterprise_id, compliance_year) KEY (enterprise_id, compliance_year) "
                + "VALUES (?, ?)", enterpriseId, complianceYear);
    }

    /**
     * 锁定企业+履约年度串行锁；同一企业同一年度的并发清缴申请在此按提交顺序排队。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockComplianceYear(long enterpriseId, int complianceYear) {
        jdbc.queryForRowSet(
                "SELECT enterprise_id FROM compliance_lock WHERE enterprise_id = ? AND compliance_year = ? FOR UPDATE",
                enterpriseId, complianceYear);
    }

    /**
     * 查询企业某类账户的可用批次并加行锁，按“先进先出＋到期日近者优先”排序：
     * 获得日期升序，同获得日期到期日近者优先，再以批次ID稳定排序。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BatchRow> findActiveBatchesForUpdate(long enterpriseId, String accountType) {
        String sql = "SELECT id, " + batchNoColumn(accountType) + " AS batch_no, total_amount, remaining_amount, "
                + "issued_date, expiry_date, status FROM " + tableName(accountType)
                + " WHERE enterprise_id = ? AND status = 'ACTIVE' AND remaining_amount > 0 "
                + "ORDER BY issued_date ASC, expiry_date ASC, id ASC FOR UPDATE";
        return jdbc.query(sql, BATCH_MAPPER, enterpriseId);
    }

    /**
     * 条件扣减批次：仅当剩余量足额时扣减，返回受影响行数；0 表示余额已被改动，调用方须中止整笔申请。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deductBatch(String accountType, long batchId, BigDecimal amount) {
        String table = tableName(accountType);
        return jdbc.update("UPDATE " + table
                + " SET remaining_amount = remaining_amount - ? "
                + "WHERE id = ? AND status = 'ACTIVE' AND remaining_amount >= ?",
                amount, batchId, amount);
    }

    /**
     * 批次余额清零时将其置为“已注销/已核销”终态，不可恢复、不可再次使用。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retireBatchIfEmpty(String accountType, long batchId) {
        String table = tableName(accountType);
        jdbc.update("UPDATE " + table + " SET status = 'RETIRED' WHERE id = ? AND remaining_amount = 0", batchId);
    }

    /** 查询批次当前剩余量（断言用）。 */
    public BigDecimal remainingAmount(String accountType, String batchNo) {
        return jdbc.queryForObject("SELECT remaining_amount FROM " + tableName(accountType)
                + " WHERE " + batchNoColumn(accountType) + " = ?", BigDecimal.class, batchNo);
    }

    /** 查询批次状态（断言用）。 */
    public String batchStatus(String accountType, String batchNo) {
        return jdbc.queryForObject("SELECT status FROM " + tableName(accountType)
                + " WHERE " + batchNoColumn(accountType) + " = ?", String.class, batchNo);
    }

    /** 核定信息状态（断言/标记任务用）。 */
    public String assessmentStatus(long enterpriseId, int complianceYear) {
        return jdbc.queryForObject(
                "SELECT status FROM emission_assessment WHERE enterprise_id = ? AND compliance_year = ?",
                String.class, enterpriseId, complianceYear);
    }

    /**
     * 截止时刻已过且仍未足额（PENDING）的企业年度标记为未履约；已足额者保持 SURRENDERED 不变。
     * now 严格晚于截止时刻才标记，恰在截止时刻不标记。该条件更新在命中行上行锁，
     * 与清缴事务在同一核定行上互斥，保证截止判定与申请受理并发时只有一个结果。返回受影响行数。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int markNotComplied(Instant now) {
        return jdbc.update(
                "UPDATE emission_assessment SET status = 'NOT_COMPLIED' "
                        + "WHERE status = 'PENDING' AND deadline_utc < ?",
                Timestamp.from(now));
    }

    /**
     * 足额清缴完成后将核定状态置为 SURRENDERED；仅允许从 PENDING 迁移，
     * 若截止标记抢先置为 NOT_COMPLIED 则更新0行，调用方据此整笔回滚。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int markSurrendered(long assessmentId) {
        return jdbc.update(
                "UPDATE emission_assessment SET status = 'SURRENDERED' WHERE id = ? AND status = 'PENDING'",
                assessmentId);
    }

    private static String tableName(String accountType) {
        if (TYPE_ALLOWANCE.equals(accountType)) {
            return "allowance_batch";
        }
        if (TYPE_OFFSET.equals(accountType)) {
            return "offset_credit";
        }
        throw new IllegalArgumentException("unknown account type: " + accountType);
    }

    private static String batchNoColumn(String accountType) {
        return TYPE_OFFSET.equals(accountType) ? "credit_no" : "batch_no";
    }

    private static final class BatchRowMapper implements RowMapper<BatchRow> {
        @Override
        public BatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BatchRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getBigDecimal("total_amount"),
                    rs.getBigDecimal("remaining_amount"),
                    rs.getDate("issued_date").toLocalDate(),
                    rs.getDate("expiry_date").toLocalDate(),
                    rs.getString("status"));
        }
    }
}
