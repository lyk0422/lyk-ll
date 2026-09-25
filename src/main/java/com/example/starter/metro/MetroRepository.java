package com.example.starter.metro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 清分数据访问：交易流水、幂等记录、清分批次、清分明细与对账差异。
 */
@Repository
public class MetroRepository {

    private final JdbcTemplate jdbcTemplate;

    public MetroRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<TxnRow> TXN_MAPPER = (rs, rowNum) -> new TxnRow(
            rs.getLong("id"),
            rs.getString("txn_no"),
            rs.getString("card_id"),
            rs.getString("entry_station"),
            rs.getTimestamp("entry_time").toLocalDateTime(),
            rs.getString("exit_station"),
            toDateTime(rs, "exit_time"),
            rs.getString("status"),
            toLong(rs, "distance_m"),
            rs.getLong("chain_base_m"),
            toLong(rs, "fare_cents"),
            rs.getLong("penalty_cents"),
            rs.getInt("transfer_flag") == 1,
            rs.getInt("estimated_flag") == 1,
            toLong(rs, "batch_id"),
            toDate(rs, "biz_date"),
            rs.getString("request_id"),
            rs.getString("fingerprint"));

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("id"),
            rs.getDate("biz_date").toLocalDate(),
            rs.getString("status"),
            rs.getLong("total_cents"),
            rs.getInt("txn_count"),
            rs.getString("seal_request_id"),
            toDateTime(rs, "sealed_at"));

    private static final RowMapper<DetailRow> DETAIL_MAPPER = (rs, rowNum) -> new DetailRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("txn_no"),
            rs.getString("line_code"),
            rs.getLong("distance_m"),
            rs.getBigDecimal("ratio").toPlainString(),
            rs.getLong("amount_cents"),
            rs.getString("entry_type"));

    private static final RowMapper<DiscrepancyRow> DISCREPANCY_MAPPER = (rs, rowNum) -> new DiscrepancyRow(
            rs.getLong("id"),
            rs.getDate("biz_date").toLocalDate(),
            rs.getString("txn_no"),
            rs.getLong("amount_cents"),
            rs.getString("reason"));

    private static final RowMapper<IdemRow> IDEM_MAPPER = (rs, rowNum) -> new IdemRow(
            rs.getString("scope"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("response_body"));

    private static LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Long toLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate toDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static final String TXN_COLUMNS =
            "id, txn_no, card_id, entry_station, entry_time, exit_station, exit_time, status,"
            + " distance_m, chain_base_m, fare_cents, penalty_cents, transfer_flag, estimated_flag,"
            + " batch_id, biz_date, request_id, fingerprint";

    /** 按流水号查询交易。 */
    public Optional<TxnRow> findTxnByNo(String txnNo) {
        List<TxnRow> rows = jdbcTemplate.query(
                "SELECT " + TXN_COLUMNS + " FROM metro_transaction WHERE txn_no = ?",
                TXN_MAPPER, txnNo);
        return rows.stream().findFirst();
    }

    /** 按流水号查询交易并加行锁（SELECT ... FOR UPDATE）。 */
    public Optional<TxnRow> findTxnByNoForUpdate(String txnNo) {
        List<TxnRow> rows = jdbcTemplate.query(
                "SELECT " + TXN_COLUMNS + " FROM metro_transaction WHERE txn_no = ? FOR UPDATE",
                TXN_MAPPER, txnNo);
        return rows.stream().findFirst();
    }

    /** 插入进闸交易，返回主键；流水号重复抛出 DuplicateKeyException。 */
    public long insertTxn(TxnRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO metro_transaction (txn_no, card_id, entry_station, entry_time, status,"
                    + " chain_base_m, penalty_cents, transfer_flag, estimated_flag, request_id, fingerprint,"
                    + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.txnNo());
            ps.setString(2, row.cardId());
            ps.setString(3, row.entryStation());
            ps.setTimestamp(4, Timestamp.valueOf(row.entryTime()));
            ps.setString(5, row.status());
            ps.setLong(6, row.chainBaseM());
            ps.setLong(7, row.penaltyCents());
            ps.setInt(8, row.transfer() ? 1 : 0);
            ps.setInt(9, row.estimated() ? 1 : 0);
            ps.setString(10, row.requestId());
            ps.setString(11, row.fingerprint());
            ps.setTimestamp(12, Timestamp.valueOf(now));
            ps.setTimestamp(13, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 出闸/暂记/补登后更新交易结算字段。 */
    public void updateTxnSettlement(TxnRow row) {
        jdbcTemplate.update(
                "UPDATE metro_transaction SET exit_station = ?, exit_time = ?, status = ?, distance_m = ?,"
                + " chain_base_m = ?, fare_cents = ?, penalty_cents = ?, transfer_flag = ?, estimated_flag = ?,"
                + " batch_id = ?, biz_date = ?, updated_at = ? WHERE id = ?",
                row.exitStation(),
                row.exitTime() == null ? null : Timestamp.valueOf(row.exitTime()),
                row.status(),
                row.distanceM(),
                row.chainBaseM(),
                row.fareCents(),
                row.penaltyCents(),
                row.transfer() ? 1 : 0,
                row.estimated() ? 1 : 0,
                row.batchId(),
                row.bizDate() == null ? null : java.sql.Date.valueOf(row.bizDate()),
                Timestamp.valueOf(LocalDateTime.now()),
                row.id());
    }

    /** 查询票卡最近一次已有出闸时间的交易（用于换乘链判定）。 */
    public Optional<TxnRow> findLatestExitedTxnForCard(String cardId, LocalDateTime before) {
        List<TxnRow> rows = jdbcTemplate.query(
                "SELECT " + TXN_COLUMNS + " FROM metro_transaction"
                + " WHERE card_id = ? AND exit_time IS NOT NULL AND exit_time <= ?"
                + " ORDER BY exit_time DESC, id DESC LIMIT 1",
                TXN_MAPPER, cardId, Timestamp.valueOf(before));
        return rows.stream().findFirst();
    }

    /** 按业务日期查询批次。 */
    public Optional<BatchRow> findBatchByDate(LocalDate bizDate) {
        List<BatchRow> rows = jdbcTemplate.query(
                "SELECT id, biz_date, status, total_cents, txn_count, seal_request_id, sealed_at"
                + " FROM clearing_batch WHERE biz_date = ?",
                BATCH_MAPPER, java.sql.Date.valueOf(bizDate));
        return rows.stream().findFirst();
    }

    /** 按业务日期查询批次并加行锁。 */
    public Optional<BatchRow> findBatchByDateForUpdate(LocalDate bizDate) {
        List<BatchRow> rows = jdbcTemplate.query(
                "SELECT id, biz_date, status, total_cents, txn_count, seal_request_id, sealed_at"
                + " FROM clearing_batch WHERE biz_date = ? FOR UPDATE",
                BATCH_MAPPER, java.sql.Date.valueOf(bizDate));
        return rows.stream().findFirst();
    }

    /** 创建 OPEN 批次，返回主键。 */
    public long insertBatch(LocalDate bizDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO clearing_batch (biz_date, status, total_cents, txn_count, created_at)"
                    + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1, java.sql.Date.valueOf(bizDate));
            ps.setString(2, "OPEN");
            ps.setLong(3, 0L);
            ps.setInt(4, 0);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 插入清分明细。 */
    public void insertDetail(DetailRow row) {
        jdbcTemplate.update(
                "INSERT INTO clearing_detail (batch_id, txn_no, line_code, distance_m, ratio, amount_cents,"
                + " entry_type, created_at) VALUES (?,?,?,?,?,?,?,?)",
                row.batchId(), row.txnNo(), row.lineCode(), row.distanceM(),
                new java.math.BigDecimal(row.ratio()), row.amountCents(), row.entryType(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询批次全部明细（复算用）。 */
    public List<DetailRow> findDetailsByBatch(long batchId) {
        return jdbcTemplate.query(
                "SELECT id, batch_id, txn_no, line_code, distance_m, ratio, amount_cents, entry_type"
                + " FROM clearing_detail WHERE batch_id = ? ORDER BY id",
                DETAIL_MAPPER, batchId);
    }

    /** 统计批次内已入账交易笔数（distinct 流水）。 */
    public int countTxnsInBatch(long batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT txn_no) FROM clearing_detail WHERE batch_id = ?",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /** 封账：置状态、合计快照与封账时间。 */
    public void sealBatch(long batchId, long totalCents, int txnCount, String sealRequestId,
                          LocalDateTime sealedAt) {
        jdbcTemplate.update(
                "UPDATE clearing_batch SET status = 'SEALED', total_cents = ?, txn_count = ?,"
                + " seal_request_id = ?, sealed_at = ? WHERE id = ?",
                totalCents, txnCount, sealRequestId, Timestamp.valueOf(sealedAt), batchId);
    }

    /** 登记对账差异。 */
    public void insertDiscrepancy(DiscrepancyRow row) {
        jdbcTemplate.update(
                "INSERT INTO clearing_discrepancy (biz_date, txn_no, amount_cents, reason, created_at)"
                + " VALUES (?,?,?,?,?)",
                java.sql.Date.valueOf(row.bizDate()), row.txnNo(), row.amountCents(), row.reason(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询批次相关差异登记。 */
    public List<DiscrepancyRow> findDiscrepanciesByDate(LocalDate bizDate) {
        return jdbcTemplate.query(
                "SELECT id, biz_date, txn_no, amount_cents, reason FROM clearing_discrepancy"
                + " WHERE biz_date = ? ORDER BY id",
                DISCREPANCY_MAPPER, java.sql.Date.valueOf(bizDate));
    }

    /** 查询幂等记录。 */
    public Optional<IdemRow> findIdem(String scope, String requestId) {
        List<IdemRow> rows = jdbcTemplate.query(
                "SELECT scope, request_id, fingerprint, response_body FROM idempotency_record"
                + " WHERE scope = ? AND request_id = ?",
                IDEM_MAPPER, scope, requestId);
        return rows.stream().findFirst();
    }

    /** 事务开头插入幂等占位记录（作为并发锁）；键冲突抛 DuplicateKeyException。 */
    public void insertIdemPlaceholder(String scope, String requestId, String fingerprint) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record (scope, request_id, fingerprint, response_body, created_at)"
                + " VALUES (?,?,?,'',?)",
                scope, requestId, fingerprint, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 事务提交前回写首次响应快照。 */
    public void updateIdemResponse(String scope, String requestId, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET response_body = ? WHERE scope = ? AND request_id = ?",
                responseBody, scope, requestId);
    }

    /** 按主键查询批次。 */
    public Optional<BatchRow> findBatchById(long batchId) {
        List<BatchRow> rows = jdbcTemplate.query(
                "SELECT id, biz_date, status, total_cents, txn_count, seal_request_id, sealed_at"
                + " FROM clearing_batch WHERE id = ?",
                BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 按流水号与类型查询明细（如查询暂记明细用于冲销）。 */
    public List<DetailRow> findDetailsByTxnAndType(String txnNo, String entryType) {
        return jdbcTemplate.query(
                "SELECT id, batch_id, txn_no, line_code, distance_m, ratio, amount_cents, entry_type"
                + " FROM clearing_detail WHERE txn_no = ? AND entry_type = ? ORDER BY id",
                DETAIL_MAPPER, txnNo, entryType);
    }

    /**
     * 交易流水行。
     */
    public record TxnRow(Long id, String txnNo, String cardId, String entryStation,
                         LocalDateTime entryTime, String exitStation, LocalDateTime exitTime,
                         String status, Long distanceM, long chainBaseM, Long fareCents,
                         long penaltyCents, boolean transfer, boolean estimated,
                         Long batchId, LocalDate bizDate, String requestId, String fingerprint) {
    }

    /**
     * 清分批次行。
     */
    public record BatchRow(long id, LocalDate bizDate, String status, long totalCents,
                           int txnCount, String sealRequestId, LocalDateTime sealedAt) {
    }

    /**
     * 清分明细行。
     */
    public record DetailRow(Long id, long batchId, String txnNo, String lineCode,
                            long distanceM, String ratio, long amountCents, String entryType) {
    }

    /**
     * 对账差异行。
     */
    public record DiscrepancyRow(Long id, LocalDate bizDate, String txnNo, long amountCents, String reason) {
    }

    /**
     * 幂等记录行。
     */
    public record IdemRow(String scope, String requestId, String fingerprint, String responseBody) {
    }
}
