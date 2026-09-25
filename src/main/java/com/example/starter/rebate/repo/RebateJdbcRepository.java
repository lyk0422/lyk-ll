package com.example.starter.rebate.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 退税申报全部表的 JDBC 仓储，基于 H2（MODE=MySQL），全部使用参数化 SQL。
 */
@Repository
public class RebateJdbcRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RebateJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 退税率文库 ----------------

    /** 按商品编码+生效日录入（合成数据），同版本覆盖。 */
    public void upsertRate(String hsCode, LocalDate effectiveDate, BigDecimal rate) {
        jdbc.update("""
                MERGE INTO rate_library (hs_code, effective_date, rate, created_at)
                KEY (hs_code, effective_date)
                VALUES (:hsCode, :effectiveDate, :rate, CURRENT_TIMESTAMP)
                """,
                new MapSqlParameterSource()
                        .addValue("hsCode", hsCode)
                        .addValue("effectiveDate", effectiveDate)
                        .addValue("rate", rate));
    }

    /** 取申报日（含）之前最新生效的文库版本，无版本返回 null。 */
    public RateLibraryRow findEffectiveRate(String hsCode, LocalDate declareDate) {
        List<RateLibraryRow> rows = jdbc.query("""
                        SELECT id, hs_code, effective_date, rate FROM rate_library
                        WHERE hs_code = :hsCode AND effective_date <= :declareDate
                        ORDER BY effective_date DESC, id DESC
                        LIMIT 1
                        """,
                new MapSqlParameterSource()
                        .addValue("hsCode", hsCode)
                        .addValue("declareDate", declareDate),
                RATE_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 单证 ----------------

    public long insertDocument(String declarationNo, String invoiceNo, String hsCode,
                               BigDecimal fobRmb, BigDecimal fobUsd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                        INSERT INTO rebate_document
                            (declaration_no, invoice_no, hs_code, fob_rmb, fob_usd, status, batch_id)
                        VALUES (:declarationNo, :invoiceNo, :hsCode, :fobRmb, :fobUsd, 'COLLECTED', NULL)
                        """,
                new MapSqlParameterSource()
                        .addValue("declarationNo", declarationNo)
                        .addValue("invoiceNo", invoiceNo)
                        .addValue("hsCode", hsCode)
                        .addValue("fobRmb", fobRmb)
                        .addValue("fobUsd", fobUsd),
                keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    public DocumentRow findDocumentByPair(String declarationNo, String invoiceNo) {
        List<DocumentRow> rows = jdbc.query("""
                        SELECT * FROM rebate_document
                        WHERE declaration_no = :declarationNo AND invoice_no = :invoiceNo
                        """,
                new MapSqlParameterSource()
                        .addValue("declarationNo", declarationNo)
                        .addValue("invoiceNo", invoiceNo),
                DOC_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public DocumentRow findDocumentById(Long id) {
        List<DocumentRow> rows = jdbc.query("SELECT * FROM rebate_document WHERE id = :id",
                new MapSqlParameterSource("id", id), DOC_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DocumentRow> findDocumentsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM rebate_document WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), DOC_MAPPER);
    }

    public List<DocumentRow> findDocumentsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM rebate_document WHERE batch_id = :batchId ORDER BY id",
                new MapSqlParameterSource("batchId", batchId), DOC_MAPPER);
    }

    /**
     * 报关单号是否已被有效批次占用（撤批作废会删除占用登记）。
     */
    public boolean isDeclarationRegistered(String declarationNo) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM declaration_registration WHERE declaration_no = :declarationNo",
                new MapSqlParameterSource("declarationNo", declarationNo), Integer.class);
        return cnt != null && cnt > 0;
    }

    /** 占用报关单号；并发重复占用由主键唯一约束兜底（抛 DuplicateKeyException）。 */
    public void registerDeclaration(String declarationNo, long batchId) {
        jdbc.update("""
                        INSERT INTO declaration_registration (declaration_no, batch_id)
                        VALUES (:declarationNo, :batchId)
                        """,
                new MapSqlParameterSource()
                        .addValue("declarationNo", declarationNo)
                        .addValue("batchId", batchId));
    }

    /** 撤批时删除该批次全部报关单号占用，释放后可重新参与新批次。 */
    public int deleteDeclarationRegistration(long batchId) {
        return jdbc.update("DELETE FROM declaration_registration WHERE batch_id = :batchId",
                new MapSqlParameterSource("batchId", batchId));
    }

    /** 单证关联进批次；仅允许未挂批次的已采集/已释放单证。 */
    public int attachDocuments(List<Long> docIds, long batchId) {
        return jdbc.update("""
                        UPDATE rebate_document
                        SET batch_id = :batchId, status = 'COLLECTED', released_at = NULL
                        WHERE id IN (:ids) AND batch_id IS NULL AND status IN ('COLLECTED', 'RELEASED')
                        """,
                new MapSqlParameterSource()
                        .addValue("ids", docIds)
                        .addValue("batchId", batchId));
    }

    /** 撤批：整体释放批次下全部单证。 */
    public int releaseDocumentsByBatch(long batchId, Instant releasedAt) {
        return jdbc.update("""
                        UPDATE rebate_document
                        SET batch_id = NULL, status = 'RELEASED', released_at = :releasedAt
                        WHERE batch_id = :batchId
                        """,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("releasedAt", Timestamp.from(releasedAt)));
    }

    // ---------------- 批次 ----------------

    public long insertBatch(String batchNo, LocalDate declareDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                        INSERT INTO rebate_batch (batch_no, declare_date, status, tax_total)
                        VALUES (:batchNo, :declareDate, 'DRAFT', 0)
                        """,
                new MapSqlParameterSource()
                        .addValue("batchNo", batchNo)
                        .addValue("declareDate", declareDate),
                keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    public BatchRow findBatchById(Long id) {
        List<BatchRow> rows = jdbc.query("SELECT * FROM rebate_batch WHERE id = :id",
                new MapSqlParameterSource("id", id), BATCH_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BatchRow findBatchByNo(String batchNo) {
        List<BatchRow> rows = jdbc.query("SELECT * FROM rebate_batch WHERE batch_no = :batchNo",
                new MapSqlParameterSource("batchNo", batchNo), BATCH_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateTaxTotal(long batchId, BigDecimal taxTotal) {
        jdbc.update("UPDATE rebate_batch SET tax_total = :taxTotal WHERE id = :id",
                new MapSqlParameterSource().addValue("taxTotal", taxTotal).addValue("id", batchId));
    }

    /** DRAFT→DECLARED 条件更新，返回受影响行数（0 表示状态已变化，并发/冻结保护）。 */
    public int declareBatch(long batchId, Instant at) {
        return jdbc.update("""
                        UPDATE rebate_batch SET status = 'DECLARED', declared_at = :at
                        WHERE id = :id AND status = 'DRAFT'
                        """,
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", batchId));
    }

    public int approveBatch(long batchId, Instant at) {
        return jdbc.update("""
                        UPDATE rebate_batch SET status = 'APPROVED', approved_at = :at
                        WHERE id = :id AND status = 'DECLARED'
                        """,
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", batchId));
    }

    public int markRefunded(long batchId, Instant at) {
        return jdbc.update("""
                        UPDATE rebate_batch SET status = 'REFUNDED', refunded_at = :at
                        WHERE id = :id AND status = 'APPROVED'
                        """,
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", batchId));
    }

    public int voidBatch(long batchId, Instant at) {
        return jdbc.update("""
                        UPDATE rebate_batch SET status = 'VOID', voided_at = :at
                        WHERE id = :id AND status = 'DRAFT'
                        """,
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", batchId));
    }

    public int closeBatch(long batchId, Instant at) {
        return jdbc.update("""
                        UPDATE rebate_batch SET status = 'CLOSED', closed_at = :at
                        WHERE id = :id AND status = 'REFUNDED'
                        """,
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", batchId));
    }

    // ---------------- 校验明细 ----------------

    public void insertValidationDetail(long batchId, long docId, String hsCode, BigDecimal rate,
                                       LocalDate rateEffectiveDate, BigDecimal taxBase,
                                       BigDecimal cost, String result) {
        jdbc.update("""
                        INSERT INTO validation_detail
                            (batch_id, doc_id, hs_code, rate, rate_effective_date, tax_base, cost, result)
                        VALUES (:batchId, :docId, :hsCode, :rate, :rateEffectiveDate, :taxBase, :cost, :result)
                        """,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("docId", docId)
                        .addValue("hsCode", hsCode)
                        .addValue("rate", rate)
                        .addValue("rateEffectiveDate", rateEffectiveDate)
                        .addValue("taxBase", taxBase)
                        .addValue("cost", cost)
                        .addValue("result", result));
    }

    public List<ValidationDetailRow> findDetailsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM validation_detail WHERE batch_id = :batchId ORDER BY doc_id",
                new MapSqlParameterSource("batchId", batchId), DETAIL_MAPPER);
    }

    // ---------------- 疑点 ----------------

    public long insertOpenDoubt(long batchId, long docId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO doubt (batch_id, doc_id, status) VALUES (:batchId, :docId, 'OPEN')",
                new MapSqlParameterSource().addValue("batchId", batchId).addValue("docId", docId),
                keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    public DoubtRow findDoubtById(Long id) {
        List<DoubtRow> rows = jdbc.query("SELECT * FROM doubt WHERE id = :id",
                new MapSqlParameterSource("id", id), DOUBT_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DoubtRow> findDoubtsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM doubt WHERE batch_id = :batchId ORDER BY id",
                new MapSqlParameterSource("batchId", batchId), DOUBT_MAPPER);
    }

    public long countUnclearedDoubts(long batchId) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM doubt WHERE batch_id = :batchId AND status <> 'CLEARED'",
                new MapSqlParameterSource("batchId", batchId), Long.class);
        return cnt == null ? 0 : cnt;
    }

    /** OPEN→EXPLAINED，返回受影响行数。 */
    public int markDoubtExplained(long doubtId, String explanation, String requestId, Instant at) {
        return jdbc.update("""
                        UPDATE doubt
                        SET status = 'EXPLAINED', explanation = :explanation,
                            explain_request_id = :requestId, explained_at = :at
                        WHERE id = :id AND status = 'OPEN'
                        """,
                new MapSqlParameterSource()
                        .addValue("explanation", explanation)
                        .addValue("requestId", requestId)
                        .addValue("at", Timestamp.from(at))
                        .addValue("id", doubtId));
    }

    /** EXPLAINED→CLEARED（复核确认），返回受影响行数。 */
    public int markDoubtReviewed(long doubtId, Instant at) {
        return jdbc.update("""
                        UPDATE doubt SET status = 'CLEARED', reviewed_at = :at
                        WHERE id = :id AND status = 'EXPLAINED'
                        """,
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("id", doubtId));
    }

    // ---------------- 幂等登记 ----------------

    public IdempotentRow findIdempotent(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT * FROM idempotent_request WHERE request_id = :requestId",
                new MapSqlParameterSource("requestId", requestId), IDEMPOTENT_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertIdempotent(String requestId, String bizType, String fingerprint,
                                 int statusCode, String responseJson) {
        jdbc.update("""
                        INSERT INTO idempotent_request
                            (request_id, biz_type, fingerprint, status_code, response_json)
                        VALUES (:requestId, :bizType, :fingerprint, :statusCode, :responseJson)
                        """,
                new MapSqlParameterSource()
                        .addValue("requestId", requestId)
                        .addValue("bizType", bizType)
                        .addValue("fingerprint", fingerprint)
                        .addValue("statusCode", statusCode)
                        .addValue("responseJson", responseJson));
    }

    // ---------------- 退库核销 ----------------

    public long insertRefund(long batchId, String treasurySerialNo, BigDecimal declaredTax,
                             BigDecimal receivedAmount, BigDecimal diffRatio, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                        INSERT INTO refund_registration
                            (batch_id, treasury_serial_no, declared_tax, received_amount, diff_ratio, status)
                        VALUES (:batchId, :serialNo, :declaredTax, :receivedAmount, :diffRatio, :status)
                        """,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("serialNo", treasurySerialNo)
                        .addValue("declaredTax", declaredTax)
                        .addValue("receivedAmount", receivedAmount)
                        .addValue("diffRatio", diffRatio)
                        .addValue("status", status),
                keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    public RefundRow findRefundById(long id) {
        List<RefundRow> rows = jdbc.query("SELECT * FROM refund_registration WHERE id = :id",
                new MapSqlParameterSource("id", id), REFUND_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RefundRow findRefund(long batchId, String treasurySerialNo) {        List<RefundRow> rows = jdbc.query("""
                        SELECT * FROM refund_registration
                        WHERE batch_id = :batchId AND treasury_serial_no = :serialNo
                        """,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("serialNo", treasurySerialNo),
                REFUND_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RefundRow> findRefundsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM refund_registration WHERE batch_id = :batchId ORDER BY id",
                new MapSqlParameterSource("batchId", batchId), REFUND_MAPPER);
    }

    // ---------------- 迟到事件 ----------------

    public long insertLateEvent(long batchId, String declarationNo, String invoiceNo, String payload) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                        INSERT INTO late_event (batch_id, declaration_no, invoice_no, payload)
                        VALUES (:batchId, :declarationNo, :invoiceNo, :payload)
                        """,
                new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("declarationNo", declarationNo)
                        .addValue("invoiceNo", invoiceNo)
                        .addValue("payload", payload),
                keyHolder, new String[]{"id"});
        return keyHolder.getKey().longValue();
    }

    public List<LateEventRow> findLateEventsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM late_event WHERE batch_id = :batchId ORDER BY id",
                new MapSqlParameterSource("batchId", batchId), LATE_MAPPER);
    }

    public LateEventRow findLateEventById(Long id) {
        List<LateEventRow> rows = jdbc.query("SELECT * FROM late_event WHERE id = :id",
                new MapSqlParameterSource("id", id), LATE_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- RowMappers ----------------

    private static final RowMapper<RateLibraryRow> RATE_MAPPER = (rs, n) -> new RateLibraryRow(
            rs.getLong("id"), rs.getString("hs_code"), rs.getObject("effective_date", LocalDate.class),
            rs.getBigDecimal("rate"));

    private static final RowMapper<DocumentRow> DOC_MAPPER = (rs, n) -> new DocumentRow(
            rs.getLong("id"), rs.getString("declaration_no"), rs.getString("invoice_no"),
            rs.getString("hs_code"), rs.getBigDecimal("fob_rmb"), rs.getBigDecimal("fob_usd"),
            rs.getString("status"), rs.getObject("batch_id", Long.class),
            rs.getTimestamp("collected_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant());

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_no"), rs.getObject("declare_date", LocalDate.class),
            rs.getString("status"), rs.getBigDecimal("tax_total"),
            rs.getTimestamp("created_at").toInstant(),
            instantOrNull(rs, "declared_at"), instantOrNull(rs, "approved_at"),
            instantOrNull(rs, "refunded_at"), instantOrNull(rs, "voided_at"),
            instantOrNull(rs, "closed_at"));

    private static final RowMapper<ValidationDetailRow> DETAIL_MAPPER = (rs, n) -> new ValidationDetailRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("doc_id"), rs.getString("hs_code"),
            rs.getBigDecimal("rate"), rs.getObject("rate_effective_date", LocalDate.class),
            rs.getBigDecimal("tax_base"), rs.getBigDecimal("cost"), rs.getString("result"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<DoubtRow> DOUBT_MAPPER = (rs, n) -> new DoubtRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("doc_id"), rs.getString("status"),
            rs.getString("explanation"), rs.getString("explain_request_id"),
            rs.getTimestamp("created_at").toInstant(),
            instantOrNull(rs, "explained_at"), instantOrNull(rs, "reviewed_at"));

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"), rs.getString("biz_type"), rs.getString("fingerprint"),
            rs.getInt("status_code"), rs.getString("response_json"));

    private static final RowMapper<RefundRow> REFUND_MAPPER = (rs, n) -> new RefundRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("treasury_serial_no"),
            rs.getBigDecimal("declared_tax"), rs.getBigDecimal("received_amount"),
            rs.getBigDecimal("diff_ratio"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<LateEventRow> LATE_MAPPER = (rs, n) -> new LateEventRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("declaration_no"),
            rs.getString("invoice_no"), rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant());

    private static Instant instantOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
