package com.example.starter.slaughter.persistence;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.domain.ChainStatus;
import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.domain.InventoryScope;
import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.domain.PreQuarantineResult;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 屠宰检疫数据访问：批次、头只台账、检验链、回执、库存标识、迟到事件与幂等记录。
 * 所有更新均为参数化 SQL；批次更新带 version 乐观锁，返回受影响行数。
 */
@Repository
public class SlaughterRepository {

    private final JdbcTemplate jdbc;

    public SlaughterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- 批次 ----

    public void insertBatch(String batchNo, int headCount, Instant now, Instant validUntil) {
        jdbc.update("""
                INSERT INTO slaughter_batch
                    (batch_no, status, head_count, declared_at_utc, valid_until_utc,
                     redeclare_no, version, created_at_utc, updated_at_utc)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)
                """,
                batchNo, BatchStatus.DECLARED.name(), headCount,
                Timestamp.from(now), Timestamp.from(validUntil),
                Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<BatchRow> findBatch(String batchNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, batch_no, status, head_count, declared_at_utc, pre_quarantine_result, "
                            + "pre_quarantine_at_utc, valid_until_utc, inspection_chain_id, judged_at_utc, "
                            + "checked_out_at_utc, redeclare_no, version "
                            + "FROM slaughter_batch WHERE batch_no = ?",
                    new BatchRowMapper(), batchNo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 乐观锁更新宰前检疫结论与有效期；返回受影响行数，0 表示版本冲突。 */
    public int updatePreQuarantine(String batchNo, PreQuarantineResult result, Instant now,
                                   Instant validUntil, int expectedVersion) {
        return jdbc.update("""
                UPDATE slaughter_batch
                SET pre_quarantine_result = ?, pre_quarantine_at_utc = ?, valid_until_utc = ?,
                    status = CASE WHEN ? = 'PASS' THEN 'PRE_QUARANTINED' ELSE 'QUARANTINE_REJECTED' END,
                    version = version + 1, updated_at_utc = ?
                WHERE batch_no = ? AND version = ?
                """,
                result.name(), ts(now), ts(validUntil), result.name(),
                ts(now), batchNo, expectedVersion);
    }

    /** 乐观锁绑定检验链并进入 INSPECTING；返回受影响行数。 */
    public int updateChainId(String batchNo, long chainId, BatchStatus status, Instant now,
                             int expectedVersion) {
        return jdbc.update("""
                UPDATE slaughter_batch
                SET inspection_chain_id = ?, status = ?, version = version + 1, updated_at_utc = ?
                WHERE batch_no = ? AND version = ?
                """,
                chainId, status.name(), Timestamp.from(now), batchNo, expectedVersion);
    }

    /** 乐观锁仅更新状态；返回受影响行数。 */
    public int updateBatchStatus(String batchNo, BatchStatus status, Instant now, int expectedVersion) {
        return jdbc.update("""
                UPDATE slaughter_batch
                SET status = ?, version = version + 1, updated_at_utc = ?
                WHERE batch_no = ? AND version = ?
                """,
                status.name(), Timestamp.from(now), batchNo, expectedVersion);
    }

    /** 乐观锁进入判定完成状态 JUDGED；返回受影响行数。 */
    public int updateJudged(String batchNo, Instant now, int expectedVersion) {
        return jdbc.update("""
                UPDATE slaughter_batch
                SET status = 'JUDGED', judged_at_utc = ?, version = version + 1, updated_at_utc = ?
                WHERE batch_no = ? AND version = ?
                """,
                Timestamp.from(now), Timestamp.from(now), batchNo, expectedVersion);
    }

    /** 乐观锁出厂核销终态；返回受影响行数。 */
    public int updateCheckedOut(String batchNo, Instant now, int expectedVersion) {
        return jdbc.update("""
                UPDATE slaughter_batch
                SET status = 'CHECKED_OUT', checked_out_at_utc = ?, version = version + 1, updated_at_utc = ?
                WHERE batch_no = ? AND version = ?
                """,
                Timestamp.from(now), Timestamp.from(now), batchNo, expectedVersion);
    }

    /** 恰满 6 小时未屠宰重新申报：重置申报时刻、有效期并递增重新申报次数。 */
    public int updateOnRedeclare(String batchNo, Instant now, Instant validUntil, int expectedVersion) {
        return jdbc.update("""
                UPDATE slaughter_batch
                SET declared_at_utc = ?, valid_until_utc = ?,
                    pre_quarantine_result = NULL, pre_quarantine_at_utc = NULL,
                    inspection_chain_id = NULL, redeclare_no = redeclare_no + 1,
                    status = 'DECLARED', version = version + 1, updated_at_utc = ?
                WHERE batch_no = ? AND version = ?
                """,
                Timestamp.from(now), Timestamp.from(validUntil),
                Timestamp.from(now), batchNo, expectedVersion);
    }

    // ---- 头只台账 ----

    public void insertHeads(String batchNo, List<String> headNos, Instant now) {
        jdbc.batchUpdate(
                "INSERT INTO batch_head (batch_no, head_no, created_at_utc) VALUES (?, ?, ?)",
                headNos, headNos.size(), (ps, headNo) -> {
                    ps.setString(1, batchNo);
                    ps.setString(2, headNo);
                    ps.setTimestamp(3, Timestamp.from(now));
                });
    }

    public List<HeadRow> listHeads(String batchNo) {
        return jdbc.query(
                "SELECT id, batch_no, head_no, inspection_result, inspected_at_utc, judgment_category, "
                        + "judged_at_utc, inventory_scope, limited_region, heat_treatment, receipt_id "
                        + "FROM batch_head WHERE batch_no = ? ORDER BY id",
                new HeadRowMapper(), batchNo);
    }

    /** 登记单头检验结果；未知头只或重复登记更新 0 行。 */
    public int updateInspection(String batchNo, String headNo, InspectionResult result, Instant now) {
        return jdbc.update("""
                UPDATE batch_head
                SET inspection_result = ?, inspected_at_utc = ?
                WHERE batch_no = ? AND head_no = ? AND inspection_result IS NULL
                """,
                result.name(), Timestamp.from(now), batchNo, headNo);
    }

    /** 逐头判定并写库存标识字段（同事务内另写 inventory_mark）。 */
    public int updateJudgment(String batchNo, String headNo, JudgmentCategory category,
                              InventoryScope scope, String limitedRegion, Instant now) {
        boolean heat = category == JudgmentCategory.CONDITIONAL;
        return jdbc.update("""
                UPDATE batch_head
                SET judgment_category = ?, judged_at_utc = ?, inventory_scope = ?,
                    limited_region = ?, heat_treatment = ?
                WHERE batch_no = ? AND head_no = ?
                """,
                category.name(), Timestamp.from(now), scope.name(), limitedRegion, heat,
                batchNo, headNo);
    }

    /** 回执闭环后回写头只的回执 ID。 */
    public int updateHeadReceipt(String batchNo, String headNo, Long receiptId) {
        return jdbc.update(
                "UPDATE batch_head SET receipt_id = ? WHERE batch_no = ? AND head_no = ?",
                receiptId, batchNo, headNo);
    }

    // ---- 检验链 ----

    /** 开启检验链；同批次唯一约束冲突时抛 DuplicateKeyException。 */
    public long insertChain(String batchNo, int expectedCount, Instant now) {
        jdbc.update("""
                INSERT INTO inspection_chain (batch_no, status, inspected_count, expected_count, opened_at_utc)
                VALUES (?, 'OPEN', 0, ?, ?)
                """,
                batchNo, expectedCount, Timestamp.from(now));
        return jdbc.queryForObject(
                "SELECT id FROM inspection_chain WHERE batch_no = ?", Long.class, batchNo);
    }

    public Optional<ChainRow> findChain(String batchNo) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, batch_no, status, inspected_count, expected_count, opened_at_utc, completed_at_utc "
                            + "FROM inspection_chain WHERE batch_no = ?",
                    new ChainRowMapper(), batchNo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public int updateChainStatus(String batchNo, ChainStatus status, int inspectedCount,
                                 Instant completedAt) {
        return jdbc.update("""
                UPDATE inspection_chain
                SET status = ?, inspected_count = ?, completed_at_utc = ?
                WHERE batch_no = ?
                """,
                status.name(), inspectedCount,
                completedAt == null ? null : Timestamp.from(completedAt), batchNo);
    }

    public int incrementInspectedCount(String batchNo, int delta) {
        return jdbc.update(
                "UPDATE inspection_chain SET inspected_count = inspected_count + ? WHERE batch_no = ?",
                delta, batchNo);
    }

    // ---- 回执 ----

    public long insertReceipt(String receiptNo, String batchNo, JudgmentCategory category, int headCount,
                              boolean accepted, boolean late, boolean alerted, Instant now) {
        jdbc.update("""
                INSERT INTO disposal_receipt
                    (receipt_no, batch_no, category, head_count, accepted, late, alerted, received_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receiptNo, batchNo, category.name(), headCount, accepted, late, alerted,
                Timestamp.from(now));
        return jdbc.queryForObject(
                "SELECT id FROM disposal_receipt WHERE receipt_no = ?", Long.class, receiptNo);
    }

    public void insertReceiptHeads(long receiptId, String batchNo, List<String> headNos, Instant now) {
        jdbc.batchUpdate(
                "INSERT INTO receipt_head (receipt_id, batch_no, head_no, created_at_utc) VALUES (?, ?, ?, ?)",
                headNos, headNos.size(), (ps, headNo) -> {
                    ps.setLong(1, receiptId);
                    ps.setString(2, batchNo);
                    ps.setString(3, headNo);
                    ps.setTimestamp(4, Timestamp.from(now));
                });
    }

    public List<ReceiptRow> listReceipts(String batchNo) {
        return jdbc.query(
                "SELECT id, receipt_no, batch_no, category, head_count, accepted, late, alerted, received_at_utc "
                        + "FROM disposal_receipt WHERE batch_no = ? ORDER BY id",
                new ReceiptRowMapper(), batchNo);
    }

    /** 某张回执登记覆盖的头只编号（按插入顺序）。 */
    public List<String> listReceiptHeads(long receiptId) {
        return jdbc.queryForList(
                "SELECT head_no FROM receipt_head WHERE receipt_id = ? ORDER BY id",
                String.class, receiptId);
    }

    // ---- 库存标识 ----

    public void insertInventoryMark(String batchNo, String headNo, JudgmentCategory category,
                                    InventoryScope scope, String limitedRegion, boolean heatTreatment,
                                    Instant now) {
        jdbc.update("""
                INSERT INTO inventory_mark (batch_no, head_no, category, scope, limited_region,
                                            heat_treatment, marked_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                batchNo, headNo, category.name(), scope.name(), limitedRegion, heatTreatment,
                Timestamp.from(now));
    }

    public int countInventoryMarks(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_mark WHERE batch_no = ?", Integer.class, batchNo);
        return count == null ? 0 : count;
    }

    // ---- 迟到事件 ----

    public void insertLateEvent(String batchNo, String receiptNo, JudgmentCategory category,
                                int headCount, Instant now) {
        jdbc.update("""
                INSERT INTO late_receipt_event (batch_no, receipt_no, category, head_count, occurred_at_utc, alerted)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                batchNo, receiptNo, category.name(), headCount, Timestamp.from(now));
    }

    public int countLateEvents(String batchNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_receipt_event WHERE batch_no = ?", Integer.class, batchNo);
        return count == null ? 0 : count;
    }

    // ---- 幂等记录 ----

    public Optional<IdempotentRecord> findIdempotent(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT request_id, operation, fingerprint, http_status, response_body, created_at_utc "
                            + "FROM idempotent_request WHERE request_id = ?",
                    new IdempotentRecordMapper(), requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void insertIdempotent(IdempotentRecord record) {
        jdbc.update("""
                INSERT INTO idempotent_request
                    (request_id, operation, fingerprint, http_status, response_body, created_at_utc)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                record.requestId(), record.operation(), record.fingerprint(),
                record.httpStatus(), record.responseBody(), Timestamp.from(record.createdAtUtc()));
    }

    /** 业务执行成功后回填首次响应正文与 HTTP 状态（与业务写入同一事务）。 */
    public void updateIdempotentResponse(String requestId, int httpStatus, String responseBody) {
        jdbc.update("""
                UPDATE idempotent_request SET http_status = ?, response_body = ? WHERE request_id = ?
                """,
                httpStatus, responseBody, requestId);
    }

    // ---- RowMappers ----

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** null 安全的 Instant → SQL Timestamp 转换。 */
    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static final class BatchRowMapper implements RowMapper<BatchRow> {
        @Override
        public BatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            String pq = rs.getString("pre_quarantine_result");
            return new BatchRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    BatchStatus.valueOf(rs.getString("status")),
                    rs.getInt("head_count"),
                    toInstant(rs.getTimestamp("declared_at_utc")),
                    pq == null ? null : PreQuarantineResult.valueOf(pq),
                    toInstant(rs.getTimestamp("pre_quarantine_at_utc")),
                    toInstant(rs.getTimestamp("valid_until_utc")),
                    (Long) rs.getObject("inspection_chain_id"),
                    toInstant(rs.getTimestamp("judged_at_utc")),
                    toInstant(rs.getTimestamp("checked_out_at_utc")),
                    rs.getInt("redeclare_no"),
                    rs.getInt("version"));
        }
    }

    private static final class HeadRowMapper implements RowMapper<HeadRow> {
        @Override
        public HeadRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            String inspection = rs.getString("inspection_result");
            String judgment = rs.getString("judgment_category");
            String scope = rs.getString("inventory_scope");
            Boolean heat = (Boolean) rs.getObject("heat_treatment");
            Long receiptId = (Long) rs.getObject("receipt_id");
            return new HeadRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getString("head_no"),
                    inspection == null ? null : InspectionResult.valueOf(inspection),
                    toInstant(rs.getTimestamp("inspected_at_utc")),
                    judgment == null ? null : JudgmentCategory.valueOf(judgment),
                    toInstant(rs.getTimestamp("judged_at_utc")),
                    scope == null ? null : InventoryScope.valueOf(scope),
                    rs.getString("limited_region"),
                    heat,
                    receiptId);
        }
    }

    private static final class ChainRowMapper implements RowMapper<ChainRow> {
        @Override
        public ChainRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChainRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    ChainStatus.valueOf(rs.getString("status")),
                    rs.getInt("inspected_count"),
                    rs.getInt("expected_count"),
                    toInstant(rs.getTimestamp("opened_at_utc")),
                    toInstant(rs.getTimestamp("completed_at_utc")));
        }
    }

    private static final class ReceiptRowMapper implements RowMapper<ReceiptRow> {
        @Override
        public ReceiptRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReceiptRow(
                    rs.getLong("id"),
                    rs.getString("receipt_no"),
                    rs.getString("batch_no"),
                    JudgmentCategory.valueOf(rs.getString("category")),
                    rs.getInt("head_count"),
                    rs.getBoolean("accepted"),
                    rs.getBoolean("late"),
                    rs.getBoolean("alerted"),
                    toInstant(rs.getTimestamp("received_at_utc")));
        }
    }

    private static final class IdempotentRecordMapper implements RowMapper<IdempotentRecord> {
        @Override
        public IdempotentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotentRecord(
                    rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getString("fingerprint"),
                    rs.getInt("http_status"),
                    rs.getString("response_body"),
                    toInstant(rs.getTimestamp("created_at_utc")));
        }
    }
}
