package com.example.starter.carbon;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 碳市场履约数据访问层：基于 JdbcTemplate 的参数化 SQL。
 * 所有写操作须在事务内调用；批次扣减使用 SELECT ... FOR UPDATE 行锁保证并发安全。
 */
@Repository
public class CarbonRepository {

    private final JdbcTemplate jdbc;

    public CarbonRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 企业行。
     */
    public record EnterpriseRow(long id, String code, String name) {
    }

    /**
     * 批次行（配额或抵消信用共用结构）。
     */
    public record BatchRow(long id, long remainingAmount, LocalDate expiryDate, String status) {
    }

    /**
     * 清缴申请行。
     */
    public record RequestRow(long id, String requestId, long enterpriseId, int complianceYear,
                             String fingerprint, long allowanceAmount, long offsetAmount,
                             String status) {
    }

    /**
     * 注销明细行。
     */
    public record ItemRow(String sourceType, long batchId, long amount, String itemStatus) {
    }

    /**
     * 凭证行。
     */
    public record CertificateRow(String certificateNo, long requestPk, long enterpriseId,
                                 int complianceYear, long totalAmount, long offsetAmount,
                                 Instant issuedAt) {
    }

    private static final RowMapper<EnterpriseRow> ENTERPRISE_MAPPER = (rs, n) ->
            new EnterpriseRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) ->
            new RequestRow(rs.getLong("id"), rs.getString("request_id"), rs.getLong("enterprise_id"),
                    rs.getInt("compliance_year"), rs.getString("fingerprint"),
                    rs.getLong("allowance_amount"), rs.getLong("offset_amount"), rs.getString("status"));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, n) ->
            new ItemRow(rs.getString("source_type"), rs.getLong("batch_id"),
                    rs.getLong("amount"), rs.getString("item_status"));

    private static final RowMapper<CertificateRow> CERTIFICATE_MAPPER = (rs, n) ->
            new CertificateRow(rs.getString("certificate_no"), rs.getLong("request_pk"),
                    rs.getLong("enterprise_id"), rs.getInt("compliance_year"),
                    rs.getLong("total_amount"), rs.getLong("offset_amount"),
                    rs.getTimestamp("issued_at").toInstant());

    // ---------- 企业 ----------

    public long insertEnterprise(String code, String name) {
        jdbc.update("INSERT INTO enterprise (code, name) VALUES (?, ?)", code, name);
        return jdbc.queryForObject("SELECT id FROM enterprise WHERE code = ?", Long.class, code);
    }

    public Optional<EnterpriseRow> findEnterpriseByCode(String code) {
        List<EnterpriseRow> rows = jdbc.query(
                "SELECT id, code, name FROM enterprise WHERE code = ?", ENTERPRISE_MAPPER, code);
        return rows.stream().findFirst();
    }

    public Optional<String> findEnterpriseCodeById(long id) {
        List<String> rows = jdbc.query("SELECT code FROM enterprise WHERE id = ?",
                (rs, n) -> rs.getString(1), id);
        return rows.stream().findFirst();
    }

    // ---------- 核定排放 ----------

    public void upsertEmission(long enterpriseId, int complianceYear, long emissionAmount) {
        int updated = jdbc.update(
                "UPDATE emission_assessment SET emission_amount = ?, assessed_at = CURRENT_TIMESTAMP "
                        + "WHERE enterprise_id = ? AND compliance_year = ?",
                emissionAmount, enterpriseId, complianceYear);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO emission_assessment (enterprise_id, compliance_year, emission_amount) "
                            + "VALUES (?, ?, ?)",
                    enterpriseId, complianceYear, emissionAmount);
        }
        upsertComplianceStatus(enterpriseId, complianceYear);
    }

    /**
     * 查询核定排放量；未核定时返回 empty。
     */
    public Optional<Long> findEmission(long enterpriseId, int complianceYear) {
        List<Long> rows = jdbc.query(
                "SELECT emission_amount FROM emission_assessment WHERE enterprise_id = ? AND compliance_year = ?",
                (rs, n) -> rs.getLong(1), enterpriseId, complianceYear);
        return rows.stream().findFirst();
    }

    // ---------- 批次账户 ----------

    public long insertAllowanceBatch(long enterpriseId, int vintageYear, long amount, LocalDate expiryDate) {
        jdbc.update(
                "INSERT INTO allowance_batch (enterprise_id, vintage_year, total_amount, remaining_amount, "
                        + "expiry_date) VALUES (?, ?, ?, ?, ?)",
                enterpriseId, vintageYear, amount, amount, expiryDate);
        return jdbc.queryForObject("SELECT MAX(id) FROM allowance_batch WHERE enterprise_id = ?",
                Long.class, enterpriseId);
    }

    public long insertOffsetBatch(long enterpriseId, String creditType, long amount, LocalDate expiryDate) {
        jdbc.update(
                "INSERT INTO offset_credit_batch (enterprise_id, credit_type, total_amount, remaining_amount, "
                        + "expiry_date) VALUES (?, ?, ?, ?, ?)",
                enterpriseId, creditType, amount, amount, expiryDate);
        return jdbc.queryForObject("SELECT MAX(id) FROM offset_credit_batch WHERE enterprise_id = ?",
                Long.class, enterpriseId);
    }

    /**
     * 按“到期日近者优先，到期日相同则先入库（先进先出）优先”排序锁定配额批次。
     */
    public List<BatchRow> lockAllowanceBatches(long enterpriseId) {
        return jdbc.query(
                "SELECT id, remaining_amount, expiry_date, status FROM allowance_batch "
                        + "WHERE enterprise_id = ? AND remaining_amount > 0 "
                        + "ORDER BY expiry_date ASC, id ASC FOR UPDATE",
                batchMapper(), enterpriseId);
    }

    /**
     * 按“到期日近者优先，到期日相同则先入库（先进先出）优先”排序锁定抵消信用批次。
     */
    public List<BatchRow> lockOffsetBatches(long enterpriseId) {
        return jdbc.query(
                "SELECT id, remaining_amount, expiry_date, status FROM offset_credit_batch "
                        + "WHERE enterprise_id = ? AND remaining_amount > 0 "
                        + "ORDER BY expiry_date ASC, id ASC FOR UPDATE",
                batchMapper(), enterpriseId);
    }

    private RowMapper<BatchRow> batchMapper() {
        return (rs, n) -> new BatchRow(rs.getLong("id"), rs.getLong("remaining_amount"),
                rs.getObject("expiry_date", LocalDate.class), rs.getString("status"));
    }

    public void updateAllowanceRemaining(long batchId, long remaining) {
        jdbc.update("UPDATE allowance_batch SET remaining_amount = ?, status = ? WHERE id = ?",
                remaining, remaining == 0 ? "EXHAUSTED" : "ACTIVE", batchId);
    }

    public void updateOffsetRemaining(long batchId, long remaining) {
        jdbc.update("UPDATE offset_credit_batch SET remaining_amount = ?, status = ? WHERE id = ?",
                remaining, remaining == 0 ? "EXHAUSTED" : "ACTIVE", batchId);
    }

    public List<BatchViewRow> listAllowanceBatches(long enterpriseId) {
        return jdbc.query(
                "SELECT id, total_amount, remaining_amount, expiry_date, status FROM allowance_batch "
                        + "WHERE enterprise_id = ? ORDER BY id ASC",
                batchViewMapper(), enterpriseId);
    }

    public List<BatchViewRow> listOffsetBatches(long enterpriseId) {
        return jdbc.query(
                "SELECT id, total_amount, remaining_amount, expiry_date, status FROM offset_credit_batch "
                        + "WHERE enterprise_id = ? ORDER BY id ASC",
                batchViewMapper(), enterpriseId);
    }

    /**
     * 批次视图行。
     */
    public record BatchViewRow(long id, long totalAmount, long remainingAmount,
                               LocalDate expiryDate, String status) {
    }

    private RowMapper<BatchViewRow> batchViewMapper() {
        return (rs, n) -> new BatchViewRow(rs.getLong("id"), rs.getLong("total_amount"),
                rs.getLong("remaining_amount"), rs.getObject("expiry_date", LocalDate.class),
                rs.getString("status"));
    }

    // ---------- 清缴申请与注销明细 ----------

    public Optional<RequestRow> findRequestByRequestId(String requestId) {
        List<RequestRow> rows = jdbc.query(
                "SELECT id, request_id, enterprise_id, compliance_year, fingerprint, allowance_amount, "
                        + "offset_amount, status FROM surrender_request WHERE request_id = ?",
                REQUEST_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<String> findRequestIdByPk(long requestPk) {
        List<String> rows = jdbc.query("SELECT request_id FROM surrender_request WHERE id = ?",
                (rs, n) -> rs.getString(1), requestPk);
        return rows.stream().findFirst();
    }

    public long insertRequest(String requestId, long enterpriseId, int complianceYear, String fingerprint,
                              long allowanceAmount, long offsetAmount) {
        jdbc.update(
                "INSERT INTO surrender_request (request_id, enterprise_id, compliance_year, fingerprint, "
                        + "allowance_amount, offset_amount, status) VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED')",
                requestId, enterpriseId, complianceYear, fingerprint, allowanceAmount, offsetAmount);
        return jdbc.queryForObject("SELECT id FROM surrender_request WHERE request_id = ?",
                Long.class, requestId);
    }

    public void insertItem(long requestPk, String sourceType, long batchId, long amount) {
        jdbc.update(
                "INSERT INTO surrender_item (request_pk, source_type, batch_id, amount, item_status) "
                        + "VALUES (?, ?, ?, ?, 'CANCELLED')",
                requestPk, sourceType, batchId, amount);
    }

    public List<ItemRow> listItems(long requestPk) {
        return jdbc.query(
                "SELECT source_type, batch_id, amount, item_status FROM surrender_item "
                        + "WHERE request_pk = ? ORDER BY id ASC",
                ITEM_MAPPER, requestPk);
    }

    /**
     * 企业年度累计已用抵消信用量（tCO2e）。
     */
    public long sumOffsetUsed(long enterpriseId, int complianceYear) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(offset_amount), 0) FROM surrender_request "
                        + "WHERE enterprise_id = ? AND compliance_year = ? AND status = 'COMPLETED'",
                Long.class, enterpriseId, complianceYear);
        return sum == null ? 0 : sum;
    }

    /**
     * 企业年度累计已注销总量（配额+抵消，tCO2e）。
     */
    public long sumSurrenderedTotal(long enterpriseId, int complianceYear) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(allowance_amount + offset_amount), 0) FROM surrender_request "
                        + "WHERE enterprise_id = ? AND compliance_year = ? AND status = 'COMPLETED'",
                Long.class, enterpriseId, complianceYear);
        return sum == null ? 0 : sum;
    }

    // ---------- 凭证 ----------

    public void insertCertificate(String certificateNo, long requestPk, long enterpriseId,
                                  int complianceYear, long totalAmount, long offsetAmount) {
        jdbc.update(
                "INSERT INTO certificate (certificate_no, request_pk, enterprise_id, compliance_year, "
                        + "total_amount, offset_amount) VALUES (?, ?, ?, ?, ?, ?)",
                certificateNo, requestPk, enterpriseId, complianceYear, totalAmount, offsetAmount);
    }

    public Optional<CertificateRow> findCertificateByRequestPk(long requestPk) {
        List<CertificateRow> rows = jdbc.query(
                "SELECT certificate_no, request_pk, enterprise_id, compliance_year, total_amount, "
                        + "offset_amount, issued_at FROM certificate WHERE request_pk = ?",
                CERTIFICATE_MAPPER, requestPk);
        return rows.stream().findFirst();
    }

    public Optional<CertificateRow> findCertificateByNo(String certificateNo) {
        List<CertificateRow> rows = jdbc.query(
                "SELECT certificate_no, request_pk, enterprise_id, compliance_year, total_amount, "
                        + "offset_amount, issued_at FROM certificate WHERE certificate_no = ?",
                CERTIFICATE_MAPPER, certificateNo);
        return rows.stream().findFirst();
    }

    // ---------- 履约状态 ----------

    /**
     * 确保履约状态行存在（默认 PENDING）。
     */
    public void upsertComplianceStatus(long enterpriseId, int complianceYear) {
        List<Long> rows = jdbc.query(
                "SELECT id FROM compliance_status WHERE enterprise_id = ? AND compliance_year = ?",
                (rs, n) -> rs.getLong(1), enterpriseId, complianceYear);
        if (rows.isEmpty()) {
            jdbc.update(
                    "INSERT INTO compliance_status (enterprise_id, compliance_year, status) "
                            + "VALUES (?, ?, 'PENDING')",
                    enterpriseId, complianceYear);
        }
    }

    public void updateComplianceStatus(long enterpriseId, int complianceYear, String status) {
        jdbc.update(
                "UPDATE compliance_status SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE enterprise_id = ? AND compliance_year = ?",
                status, enterpriseId, complianceYear);
    }

    public Optional<String> findComplianceStatus(long enterpriseId, int complianceYear) {
        List<String> rows = jdbc.query(
                "SELECT status FROM compliance_status WHERE enterprise_id = ? AND compliance_year = ?",
                (rs, n) -> rs.getString(1), enterpriseId, complianceYear);
        return rows.stream().findFirst();
    }

    /**
     * 将所有仍处于 PENDING 的年度记录标记为 NON_COMPLIANT（截止后调用）。
     *
     * @return 被标记的行数
     */
    public int markPendingAsNonCompliant() {
        return jdbc.update(
                "UPDATE compliance_status SET status = 'NON_COMPLIANT', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE status = 'PENDING'");
    }

    /**
     * 统计辅助：查询批次剩余量（测试与视图用）。
     */
    public long allowanceRemaining(long batchId) {
        return jdbc.queryForObject("SELECT remaining_amount FROM allowance_batch WHERE id = ?",
                Long.class, batchId);
    }

    public long offsetRemaining(long batchId) {
        return jdbc.queryForObject("SELECT remaining_amount FROM offset_credit_batch WHERE id = ?",
                Long.class, batchId);
    }
}
