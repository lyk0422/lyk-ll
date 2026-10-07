package com.example.starter.metro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 缴回对账域数据访问：缴回对账单、冻结来源负向明细、逐线路应缴/已缴余额、
 * 合成银行收款流水及其逐线路分配。余额占用与版本变更均为条件更新，
 * 行锁与条件 UPDATE 共同保证并发回执不超缴。
 */
@Repository
public class RemittanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public RemittanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<StatementRow> STATEMENT_MAPPER = (rs, rowNum) ->
            new StatementRow(
                    rs.getLong("id"), rs.getString("statement_no"), rs.getLong("batch_id"),
                    rs.getString("status"), rs.getLong("total_due_cents"),
                    rs.getLong("total_paid_cents"), rs.getInt("version"),
                    rs.getString("open_request_id"), toDateTime(rs, "completed_at"));

    private static final RowMapper<SourceDetailRow> SOURCE_DETAIL_MAPPER = (rs, rowNum) ->
            new SourceDetailRow(rs.getLong("id"), rs.getLong("statement_id"),
                    rs.getLong("batch_id"), rs.getLong("adjustment_id"), rs.getString("txn_no"),
                    rs.getString("line_code"), rs.getLong("signed_amount_cents"),
                    rs.getLong("due_cents"));

    private static final RowMapper<LineRow> LINE_MAPPER = (rs, rowNum) -> new LineRow(
            rs.getLong("id"), rs.getLong("statement_id"), rs.getString("line_code"),
            rs.getLong("due_cents"), rs.getLong("paid_cents"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, rowNum) -> new ReceiptRow(
            rs.getLong("id"), rs.getLong("statement_id"), rs.getString("bank_serial_no"),
            rs.getTimestamp("arrival_time_utc").toInstant(), rs.getLong("total_cents"),
            rs.getString("request_id"), rs.getString("fingerprint"), rs.getString("response_body"),
            rs.getInt("seq_no"));

    private static final RowMapper<ReceiptDetailRow> RECEIPT_DETAIL_MAPPER = (rs, rowNum) ->
            new ReceiptDetailRow(rs.getLong("id"), rs.getLong("receipt_id"),
                    rs.getLong("statement_id"), rs.getString("line_code"),
                    rs.getLong("amount_cents"));

    private static LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ---- 对账单 ----

    /** 建立缴回对账单（状态 OPEN，版本 1），返回主键。 */
    public long insertStatement(String statementNo, long batchId, long totalDueCents,
                                String openRequestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO remittance_statement (statement_no, batch_id, status, total_due_cents,"
                            + " total_paid_cents, version, open_request_id, created_at, completed_at)"
                            + " VALUES (?,?,'OPEN',?,0,1,?,?,NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, statementNo);
            ps.setLong(2, batchId);
            ps.setLong(3, totalDueCents);
            ps.setString(4, openRequestId);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按对账单号查询。 */
    public Optional<StatementRow> findStatementByNo(String statementNo) {
        return jdbcTemplate.query(statementColumns() + " FROM remittance_statement WHERE statement_no = ?",
                STATEMENT_MAPPER, statementNo).stream().findFirst();
    }

    /** 按对账单号查询并加行锁。 */
    public Optional<StatementRow> findStatementByNoForUpdate(String statementNo) {
        return jdbcTemplate.query(statementColumns()
                        + " FROM remittance_statement WHERE statement_no = ? FOR UPDATE",
                STATEMENT_MAPPER, statementNo).stream().findFirst();
    }

    /** 按来源调账批次 ID 查询（每批最多一单）。 */
    public Optional<StatementRow> findStatementByBatchId(long batchId) {
        return jdbcTemplate.query(statementColumns() + " FROM remittance_statement WHERE batch_id = ?",
                STATEMENT_MAPPER, batchId).stream().findFirst();
    }

    /** 按主键查询对账单。 */
    public Optional<StatementRow> findStatementById(long statementId) {
        return jdbcTemplate.query(statementColumns() + " FROM remittance_statement WHERE id = ?",
                STATEMENT_MAPPER, statementId).stream().findFirst();
    }

    private static String statementColumns() {
        return "SELECT id, statement_no, batch_id, status, total_due_cents, total_paid_cents,"
                + " version, open_request_id, completed_at";
    }

    // ---- 冻结来源明细 ----

    /** 建单时冻结一条来源负向明细。 */
    public void insertSourceDetail(long statementId, long batchId, long adjustmentId, String txnNo,
                                   String lineCode, long signedAmountCents, long dueCents,
                                   LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO remittance_source_detail (statement_id, batch_id, adjustment_id, txn_no,"
                        + " line_code, signed_amount_cents, due_cents, created_at) VALUES (?,?,?,?,?,?,?,?)",
                statementId, batchId, adjustmentId, txnNo, lineCode, signedAmountCents, dueCents,
                Timestamp.valueOf(now));
    }

    /** 查询对账单冻结的全部来源明细，按录入顺序。 */
    public List<SourceDetailRow> findSourceDetails(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, batch_id, adjustment_id, txn_no, line_code,"
                        + " signed_amount_cents, due_cents FROM remittance_source_detail"
                        + " WHERE statement_id = ? ORDER BY id",
                SOURCE_DETAIL_MAPPER, statementId);
    }

    // ---- 逐线路余额 ----

    /** 建单时初始化一条逐线路应缴行。 */
    public void insertLine(long statementId, String lineCode, long dueCents, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO remittance_line (statement_id, line_code, due_cents, paid_cents,"
                        + " created_at, updated_at) VALUES (?,?,?,0,?,?)",
                statementId, lineCode, dueCents, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** 查询对账单全部线路余额并加行锁，按线路编号升序。 */
    public List<LineRow> findLinesForUpdate(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, line_code, due_cents, paid_cents FROM remittance_line"
                        + " WHERE statement_id = ? ORDER BY line_code FOR UPDATE",
                LINE_MAPPER, statementId);
    }

    /** 查询对账单全部线路余额（只读），按线路编号升序。 */
    public List<LineRow> findLines(long statementId) {
        return jdbcTemplate.query(
                "SELECT id, statement_id, line_code, due_cents, paid_cents FROM remittance_line"
                        + " WHERE statement_id = ? ORDER BY line_code",
                LINE_MAPPER, statementId);
    }

    /**
     * 条件累加某线路已缴：累加后不得超过应缴。
     *
     * @return 1 成功；0 表示该线路未缴余额不足（并发超缴被拒绝，整笔回滚）
     */
    public int payLine(long statementId, String lineCode, long amountCents, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE remittance_line SET paid_cents = paid_cents + ?, updated_at = ?"
                        + " WHERE statement_id = ? AND line_code = ? AND paid_cents + ? <= due_cents",
                amountCents, Timestamp.valueOf(now), statementId, lineCode, amountCents);
    }

    // ---- 回执入账 ----

    /**
     * 对账单入账：仅 OPEN、版本匹配且累加后不超应缴时生效，已缴累加、版本加 1，
     * 全部线路恰缴齐时翻转为 COMPLETED。
     *
     * @return 1 成功；0 表示版本不符、已完成或将超缴（并发拒绝，整笔回滚）
     */
    public int applyReceiptToStatement(long statementId, long totalCents, int expectedVersion,
                                       boolean completes, LocalDateTime completedAt) {
        return jdbcTemplate.update(
                "UPDATE remittance_statement SET total_paid_cents = total_paid_cents + ?,"
                        + " version = version + 1,"
                        + " status = CASE WHEN ? = 1 THEN 'COMPLETED' ELSE status END,"
                        + " completed_at = CASE WHEN ? = 1 THEN ? ELSE completed_at END"
                        + " WHERE id = ? AND status = 'OPEN' AND version = ?"
                        + " AND total_paid_cents + ? <= total_due_cents",
                totalCents, completes ? 1 : 0, completes ? 1 : 0,
                completes ? Timestamp.valueOf(completedAt) : null,
                statementId, expectedVersion, totalCents);
    }

    /** 取下一流水序号（链内展示顺序）。 */
    public int nextReceiptSeqNo(long statementId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) FROM remittance_receipt WHERE statement_id = ?",
                Integer.class, statementId);
        return (max == null ? 0 : max) + 1;
    }

    /** 登记合成银行流水，返回主键；流水号全局唯一冲突抛 DuplicateKeyException。 */
    public long insertReceipt(long statementId, String bankSerialNo, Instant arrivalTimeUtc,
                              long totalCents, String requestId, String fingerprint,
                              String responseBody, int seqNo, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO remittance_receipt (statement_id, bank_serial_no, arrival_time_utc,"
                            + " total_cents, request_id, fingerprint, response_body, seq_no, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, statementId);
            ps.setString(2, bankSerialNo);
            ps.setTimestamp(3, Timestamp.from(arrivalTimeUtc));
            ps.setLong(4, totalCents);
            ps.setString(5, requestId);
            ps.setString(6, fingerprint);
            ps.setString(7, responseBody);
            ps.setInt(8, seqNo);
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按合成银行流水号查询。 */
    public Optional<ReceiptRow> findReceiptByBankSerial(String bankSerialNo) {
        return jdbcTemplate.query(receiptColumns() + " FROM remittance_receipt WHERE bank_serial_no = ?",
                RECEIPT_MAPPER, bankSerialNo).stream().findFirst();
    }

    /** 按合成银行流水号查询并加行锁。 */
    public Optional<ReceiptRow> findReceiptByBankSerialForUpdate(String bankSerialNo) {
        return jdbcTemplate.query(receiptColumns()
                        + " FROM remittance_receipt WHERE bank_serial_no = ? FOR UPDATE",
                RECEIPT_MAPPER, bankSerialNo).stream().findFirst();
    }

    private static String receiptColumns() {
        return "SELECT id, statement_id, bank_serial_no, arrival_time_utc, total_cents, request_id,"
                + " fingerprint, response_body, seq_no";
    }

    /** 登记流水逐线路分配。 */
    public void insertReceiptDetail(long receiptId, long statementId, String lineCode,
                                    long amountCents, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO remittance_receipt_detail (receipt_id, statement_id, line_code,"
                        + " amount_cents, created_at) VALUES (?,?,?,?,?)",
                receiptId, statementId, lineCode, amountCents, Timestamp.valueOf(now));
    }

    /** 查询某流水的逐线路分配，按线路编号升序。 */
    public List<ReceiptDetailRow> findReceiptDetails(long receiptId) {
        return jdbcTemplate.query(
                "SELECT id, receipt_id, statement_id, line_code, amount_cents"
                        + " FROM remittance_receipt_detail WHERE receipt_id = ? ORDER BY line_code",
                RECEIPT_DETAIL_MAPPER, receiptId);
    }

    /** 查询对账单全部流水（按链内序号）。 */
    public List<ReceiptRow> findReceipts(long statementId) {
        return jdbcTemplate.query(receiptColumns()
                        + " FROM remittance_receipt WHERE statement_id = ? ORDER BY seq_no",
                RECEIPT_MAPPER, statementId);
    }

    /**
     * 缴回对账单行。
     */
    public record StatementRow(long id, String statementNo, long batchId, String status,
                               long totalDueCents, long totalPaidCents, int version,
                               String openRequestId, LocalDateTime completedAt) {
    }

    /**
     * 冻结来源负向明细行。
     */
    public record SourceDetailRow(long id, long statementId, long batchId, long adjustmentId,
                                  String txnNo, String lineCode, long signedAmountCents,
                                  long dueCents) {
    }

    /**
     * 逐线路应缴/已缴行。
     */
    public record LineRow(long id, long statementId, String lineCode, long dueCents,
                          long paidCents) {
    }

    /**
     * 合成银行收款流水行。
     */
    public record ReceiptRow(long id, long statementId, String bankSerialNo, Instant arrivalTimeUtc,
                             long totalCents, String requestId, String fingerprint,
                             String responseBody, int seqNo) {
    }

    /**
     * 流水逐线路分配行。
     */
    public record ReceiptDetailRow(long id, long receiptId, long statementId, String lineCode,
                                   long amountCents) {
    }
}
