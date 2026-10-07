package com.example.starter.metro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 缴回对账数据访问：对账单、逐线路应缴/已缴、冻结来源明细、
 * 合成银行回执及其逐线路分配。所有缴款占用与状态变更均带条件更新，
 * 并发下由行锁与条件 UPDATE 共同保证不超缴、不重复入账。
 */
@Repository
public class RemittanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public RemittanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<StatementRow> STATEMENT_MAPPER = (rs, rowNum) -> new StatementRow(
            rs.getLong("id"), rs.getString("statement_no"), rs.getLong("batch_id"),
            rs.getString("batch_no"), rs.getString("status"), rs.getLong("due_total_cents"),
            rs.getLong("paid_total_cents"), rs.getInt("version"), toDateTime(rs, "completed_at"));

    private static final RowMapper<StatementLineRow> LINE_MAPPER = (rs, rowNum) -> new StatementLineRow(
            rs.getLong("id"), rs.getLong("statement_id"), rs.getString("line_code"),
            rs.getLong("due_cents"), rs.getLong("paid_cents"));

    private static final RowMapper<SourceDetailRow> SOURCE_MAPPER = (rs, rowNum) -> new SourceDetailRow(
            rs.getLong("id"), rs.getLong("statement_id"), rs.getString("adjustment_no"),
            rs.getString("txn_no"), rs.getString("line_code"), rs.getLong("amount_cents"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, rowNum) -> new ReceiptRow(
            rs.getLong("id"), rs.getLong("statement_id"), rs.getString("bank_flow_no"),
            rs.getTimestamp("arrived_at").toLocalDateTime(), rs.getLong("total_cents"),
            rs.getString("content_fingerprint"), rs.getString("response_body"),
            rs.getString("request_id"));

    private static final RowMapper<ReceiptLineRow> RECEIPT_LINE_MAPPER = (rs, rowNum) ->
            new ReceiptLineRow(rs.getLong("id"), rs.getLong("receipt_id"),
                    rs.getString("line_code"), rs.getLong("amount_cents"));

    private static LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ---- 对账单 ----

    /** 建立对账单（状态 OPEN，版本 1），返回主键；单号或批次重复抛出 DuplicateKeyException。 */
    public long insertStatement(String statementNo, long batchId, String batchNo, long dueTotalCents,
                                String openRequestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO remittance_statement (statement_no, batch_id, batch_no, status,"
                            + " due_total_cents, paid_total_cents, version, open_request_id, created_at)"
                            + " VALUES (?,?,?,'OPEN',?,0,1,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, statementNo);
            ps.setLong(2, batchId);
            ps.setString(3, batchNo);
            ps.setLong(4, dueTotalCents);
            ps.setString(5, openRequestId);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private static String statementColumns() {
        return "SELECT id, statement_no, batch_id, batch_no, status, due_total_cents,"
                + " paid_total_cents, version, completed_at";
    }

    /** 按对账单号查询。 */
    public Optional<StatementRow> findStatementByNo(String statementNo) {
        return jdbcTemplate.query(statementColumns() + " FROM remittance_statement WHERE statement_no = ?",
                STATEMENT_MAPPER, statementNo).stream().findFirst();
    }

    /** 按对账单号查询并加行锁（回执入账时串行化同一对账单）。 */
    public Optional<StatementRow> findStatementByNoForUpdate(String statementNo) {
        return jdbcTemplate.query(
                statementColumns() + " FROM remittance_statement WHERE statement_no = ? FOR UPDATE",
                STATEMENT_MAPPER, statementNo).stream().findFirst();
    }

    /** 按来源调账批次查询对账单（每批最多一单）。 */
    public Optional<StatementRow> findStatementByBatchId(long batchId) {
        return jdbcTemplate.query(statementColumns() + " FROM remittance_statement WHERE batch_id = ?",
                STATEMENT_MAPPER, batchId).stream().findFirst();
    }

    /**
     * 回执入账后更新对账单：累计已缴、版本加 1，全部线路恰缴齐时置 COMPLETED。
     *
     * @return 是否更新成功（版本不符返回 false）
     */
    public boolean markStatementPaid(long statementId, long paidTotalCents, boolean completed,
                                     LocalDateTime completedAt, int expectedVersion) {
        int rows = jdbcTemplate.update(
                "UPDATE remittance_statement SET paid_total_cents = ?, status = ?,"
                        + " version = version + 1, completed_at = ?"
                        + " WHERE id = ? AND version = ? AND status = 'OPEN'",
                paidTotalCents, completed ? "COMPLETED" : "OPEN",
                completedAt == null ? null : Timestamp.valueOf(completedAt),
                statementId, expectedVersion);
        return rows == 1;
    }

    // ---- 逐线路应缴/已缴 ----

    /** 建立对账单时冻结逐线路应缴快照。 */
    public void insertStatementLine(long statementId, String lineCode, long dueCents) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO remittance_statement_line (statement_id, line_code, due_cents,"
                        + " paid_cents, created_at, updated_at) VALUES (?,?,?,0,?,?)",
                statementId, lineCode, dueCents, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** 查询对账单全部线路行（只读），按线路编码升序。 */
    public List<StatementLineRow> findLinesByStatement(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, line_code, due_cents, paid_cents"
                        + " FROM remittance_statement_line WHERE statement_id = ? ORDER BY line_code",
                LINE_MAPPER, statementId);
    }

    /** 查询对账单全部线路行并加行锁（回执入账分摊时使用），按线路编码升序。 */
    public List<StatementLineRow> findLinesByStatementForUpdate(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, line_code, due_cents, paid_cents"
                        + " FROM remittance_statement_line WHERE statement_id = ?"
                        + " ORDER BY line_code FOR UPDATE",
                LINE_MAPPER, statementId);
    }

    /**
     * 条件缴款：该线路已缴累加后不得超过应缴。
     *
     * @return 1 表示缴款成功；0 表示超缴被拒绝（整笔回执零入账回滚）
     */
    public int payLine(long statementId, String lineCode, long amountCents) {
        return jdbcTemplate.update(
                "UPDATE remittance_statement_line SET paid_cents = paid_cents + ?, updated_at = ?"
                        + " WHERE statement_id = ? AND line_code = ? AND paid_cents + ? <= due_cents",
                amountCents, Timestamp.valueOf(LocalDateTime.now()), statementId, lineCode, amountCents);
    }

    // ---- 冻结来源明细 ----

    /** 建立对账单时复制调账批次负向明细（amountCents 为负数），永不改写。 */
    public void insertSourceDetail(long statementId, String adjustmentNo, String txnNo,
                                   String lineCode, long signedAmountCents) {
        jdbcTemplate.update(
                "INSERT INTO remittance_source_detail (statement_id, adjustment_no, txn_no,"
                        + " line_code, amount_cents, created_at) VALUES (?,?,?,?,?,?)",
                statementId, adjustmentNo, txnNo, lineCode, signedAmountCents,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询对账单冻结来源明细，按录入顺序。 */
    public List<SourceDetailRow> findSourceDetails(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, adjustment_no, txn_no, line_code, amount_cents"
                        + " FROM remittance_source_detail WHERE statement_id = ? ORDER BY id",
                SOURCE_MAPPER, statementId);
    }

    // ---- 收款凭据 ----

    /** 插入收款凭据，返回主键；银行流水号重复抛出 DuplicateKeyException。 */
    public long insertReceipt(long statementId, String bankFlowNo, LocalDateTime arrivedAtUtc,
                              long totalCents, String contentFingerprint, String responseBody,
                              String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO remittance_receipt (statement_id, bank_flow_no, arrived_at,"
                            + " total_cents, content_fingerprint, response_body, request_id, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, statementId);
            ps.setString(2, bankFlowNo);
            ps.setTimestamp(3, Timestamp.valueOf(arrivedAtUtc));
            ps.setLong(4, totalCents);
            ps.setString(5, contentFingerprint);
            ps.setString(6, responseBody);
            ps.setString(7, requestId);
            ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按合成银行流水号查询凭据（全局唯一判重）。 */
    public Optional<ReceiptRow> findReceiptByFlow(String bankFlowNo) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, bank_flow_no, arrived_at, total_cents,"
                        + " content_fingerprint, response_body, request_id"
                        + " FROM remittance_receipt WHERE bank_flow_no = ?",
                RECEIPT_MAPPER, bankFlowNo).stream().findFirst();
    }

    /** 查询对账单全部凭据，按入账顺序。 */
    public List<ReceiptRow> findReceiptsByStatement(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, bank_flow_no, arrived_at, total_cents,"
                        + " content_fingerprint, response_body, request_id"
                        + " FROM remittance_receipt WHERE statement_id = ? ORDER BY id",
                RECEIPT_MAPPER, statementId);
    }

    /** 插入凭据逐线路分配（amountCents 为正数）。 */
    public void insertReceiptLine(long receiptId, String lineCode, long amountCents) {
        jdbcTemplate.update(
                "INSERT INTO remittance_receipt_line (receipt_id, line_code, amount_cents, created_at)"
                        + " VALUES (?,?,?,?)",
                receiptId, lineCode, amountCents, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询凭据逐线路分配，按线路编码升序。 */
    public List<ReceiptLineRow> findReceiptLines(long receiptId) {
        return jdbcTemplate.query(
                "SELECT id, receipt_id, line_code, amount_cents FROM remittance_receipt_line"
                        + " WHERE receipt_id = ? ORDER BY line_code",
                RECEIPT_LINE_MAPPER, receiptId);
    }

    /**
     * 对账单行。
     */
    public record StatementRow(long id, String statementNo, long batchId, String batchNo,
                               String status, long dueTotalCents, long paidTotalCents, int version,
                               LocalDateTime completedAt) {
    }

    /**
     * 对账单逐线路行。
     */
    public record StatementLineRow(long id, long statementId, String lineCode, long dueCents,
                                   long paidCents) {
    }

    /**
     * 冻结来源明细行。
     */
    public record SourceDetailRow(long id, long statementId, String adjustmentNo, String txnNo,
                                  String lineCode, long amountCents) {
    }

    /**
     * 收款凭据行（arrivedAt 为 UTC）。
     */
    public record ReceiptRow(long id, long statementId, String bankFlowNo, LocalDateTime arrivedAt,
                             long totalCents, String contentFingerprint, String responseBody,
                             String requestId) {
    }

    /**
     * 凭据逐线路分配行。
     */
    public record ReceiptLineRow(long id, long receiptId, String lineCode, long amountCents) {
    }
}
