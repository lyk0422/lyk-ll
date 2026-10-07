package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 屠宰检疫数据访问层：全部使用参数化 SQL，时间以 UTC ISO-8601 字符串存取。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 屠宰批次行。时间字段为 UTC ISO-8601 字符串，NULL 表示环节未发生。 */
    public record BatchRow(long id, String batchNo, String species, String farmOrigin, int headCount,
                           String status, String declaredAt, String preQuarantinedAt,
                           String judgedAt, String releasedAt, boolean splitEnabled) {
    }

    /** 头只台账行。 */
    public record HeadRow(long id, long batchId, String headNo, String status, String inspectionResult,
                          String judgmentCategory, Boolean highTemp, String circulationArea,
                          boolean inventoryMarked) {
    }

    /** 判定明细行。 */
    public record JudgmentRow(long id, long batchId, String headNo, String category,
                              boolean highTemp, String circulationArea) {
    }

    /** 无害化处理回执行。 */
    public record ReceiptRow(long id, long batchId, String receiptNo, String category,
                             int headCount, String processor, boolean late, String receivedAt) {
    }

    /** 迟到事件行。 */
    public record LateEventRow(long id, long batchId, String eventType, String detail, String createdAt) {
    }

    /** 幂等登记行。 */
    public record IdempotencyRow(long id, String requestId, String endpoint, String fingerprint,
                                 String responseBody) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_no"), rs.getString("species"),
            rs.getString("farm_origin"), rs.getInt("head_count"), rs.getString("status"),
            rs.getString("declared_at"), rs.getString("pre_quarantined_at"),
            rs.getString("judged_at"), rs.getString("released_at"), rs.getBoolean("split_enabled"));

    private static final RowMapper<HeadRow> HEAD_MAPPER = (rs, n) -> new HeadRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("head_no"), rs.getString("status"),
            rs.getString("inspection_result"), rs.getString("judgment_category"),
            getNullableBoolean(rs, "high_temp"), rs.getString("circulation_area"),
            rs.getBoolean("inventory_marked"));

    private static final RowMapper<JudgmentRow> JUDGMENT_MAPPER = (rs, n) -> new JudgmentRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("head_no"), rs.getString("category"),
            rs.getBoolean("high_temp"), rs.getString("circulation_area"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, n) -> new ReceiptRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("receipt_no"), rs.getString("category"),
            rs.getInt("head_count"), rs.getString("processor"), rs.getBoolean("late"),
            rs.getString("received_at"));

    private static final RowMapper<LateEventRow> LATE_EVENT_MAPPER = (rs, n) -> new LateEventRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("event_type"),
            rs.getString("detail"), rs.getString("created_at"));

    private static final RowMapper<IdempotencyRow> IDEMPOTENCY_MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("endpoint"),
            rs.getString("fingerprint"), rs.getString("response_body"));

    private static Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    // ---------- 屠宰批次 ----------

    public void insertBatch(String batchNo, String species, String farmOrigin, int headCount,
                            String status, String declaredAt, String createdAt) {
        jdbc.update("INSERT INTO slaughter_batch(batch_no, species, farm_origin, head_count, status,"
                        + " declared_at, created_at) VALUES (?,?,?,?,?,?,?)",
                batchNo, species, farmOrigin, headCount, status, declaredAt, createdAt);
    }

    public Optional<BatchRow> findBatchByNo(String batchNo) {
        List<BatchRow> rows = jdbc.query(
                "SELECT * FROM slaughter_batch WHERE batch_no = ?", BATCH_MAPPER, batchNo);
        return rows.stream().findFirst();
    }

    /**
     * 条件状态流转：仅当前状态在 expected 内时更新，返回受影响行数（0 表示状态不满足）。
     */
    public int transitionStatus(long batchId, List<String> expected, String newStatus) {
        String placeholders = String.join(",", expected.stream().map(s -> "?").toList());
        List<Object> args = new java.util.ArrayList<>();
        args.add(newStatus);
        args.add(batchId);
        args.addAll(expected);
        return jdbc.update("UPDATE slaughter_batch SET status = ? WHERE id = ? AND status IN ("
                + placeholders + ")", args.toArray());
    }

    public int markPreQuarantined(long batchId, String at) {
        return jdbc.update("UPDATE slaughter_batch SET status = 'PRE_QUARANTINED', pre_quarantined_at = ?"
                + " WHERE id = ? AND status = 'DECLARED'", at, batchId);
    }

    /** 重新申报：重置申报时间、来源信息并清空旧头只台账（由调用方重建）。 */
    public void resetForRedeclare(long batchId, String species, String farmOrigin, int headCount,
                                  String declaredAt) {
        jdbc.update("UPDATE slaughter_batch SET species = ?, farm_origin = ?, head_count = ?,"
                        + " declared_at = ? WHERE id = ? AND status = 'DECLARED'",
                species, farmOrigin, headCount, declaredAt, batchId);
    }

    public void markJudged(long batchId, String at) {
        // 专项隔离状态承载阳性证据，判定完成不得覆盖；judged_at 照常落库以保留四类判定结果。
        jdbc.update("UPDATE slaughter_batch SET"
                + " status = CASE WHEN status = 'SPECIAL_QUARANTINE' THEN status ELSE 'JUDGED' END,"
                + " judged_at = ? WHERE id = ?", at, batchId);
    }

    /** 行锁锁定批次主行（SELECT ... FOR UPDATE），序列化风险变更/结果与出厂核销竞争。 */
    public void lockBatch(long batchId) {
        jdbc.queryForObject("SELECT id FROM slaughter_batch WHERE id = ? FOR UPDATE",
                Long.class, batchId);
    }

    public int markReleased(long batchId, String at) {
        return jdbc.update("UPDATE slaughter_batch SET status = 'RELEASED', released_at = ?"
                + " WHERE id = ? AND status = 'JUDGED'", at, batchId);
    }

    /**
     * 启用分车：仅未出厂（JUDGED/SPECIAL_QUARANTINE）批次可置位，返回受影响行数。
     * 已启用或已出厂批次返回 0，由调用方按幂等/冲突处理。
     */
    public int enableSplit(long batchId) {
        return jdbc.update("UPDATE slaughter_batch SET split_enabled = TRUE WHERE id = ?"
                + " AND split_enabled = FALSE AND status <> 'RELEASED'", batchId);
    }

    /**
     * 查询本批合格（QUALIFIED）头只标识，取自原判定明细，按头只号排序。
     * 分车出厂集合只能取自该集合，病害处置（RENDERING/DESTRUCTION）头只不在其中。
     */
    public List<String> findQualifiedHeadNos(long batchId) {
        return jdbc.queryForList(
                "SELECT head_no FROM judgment_detail WHERE batch_id = ? AND category = 'QUALIFIED'"
                        + " ORDER BY head_no",
                String.class, batchId);
    }

    /** 统计本批合格（QUALIFIED）判定头只数。 */
    public int countQualifiedHeads(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM judgment_detail WHERE batch_id = ? AND category = 'QUALIFIED'",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    // ---------- 头只台账 ----------

    public void insertHead(long batchId, String headNo, String status, String updatedAt) {
        jdbc.update("INSERT INTO head_ledger(batch_id, head_no, status, updated_at) VALUES (?,?,?,?)",
                batchId, headNo, status, updatedAt);
    }

    public void deleteHeads(long batchId) {
        jdbc.update("DELETE FROM head_ledger WHERE batch_id = ?", batchId);
    }

    public List<HeadRow> findHeads(long batchId) {
        return jdbc.query("SELECT * FROM head_ledger WHERE batch_id = ? ORDER BY head_no",
                HEAD_MAPPER, batchId);
    }

    public Optional<HeadRow> findHead(long batchId, String headNo) {
        List<HeadRow> rows = jdbc.query("SELECT * FROM head_ledger WHERE batch_id = ? AND head_no = ?",
                HEAD_MAPPER, batchId, headNo);
        return rows.stream().findFirst();
    }

    /** 登记同步检验结果：仅待检头只可更新，返回受影响行数。 */
    public int markHeadInspected(long batchId, String headNo, String result, String updatedAt) {
        return jdbc.update("UPDATE head_ledger SET status = 'INSPECTED', inspection_result = ?,"
                        + " updated_at = ? WHERE batch_id = ? AND head_no = ? AND status = 'PENDING'",
                result, updatedAt, batchId, headNo);
    }

    public int countInspectedHeads(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM head_ledger WHERE batch_id = ? AND status = 'INSPECTED'",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /** 判定分流与库存标识同事务落库：合格头只同步置库存标识。 */
    public void applyJudgment(long batchId, String headNo, String category, Boolean highTemp,
                              String circulationArea, boolean inventoryMarked, String updatedAt) {
        jdbc.update("UPDATE head_ledger SET judgment_category = ?, high_temp = ?, circulation_area = ?,"
                        + " inventory_marked = ?, updated_at = ? WHERE batch_id = ? AND head_no = ?",
                category, highTemp, circulationArea, inventoryMarked, updatedAt, batchId, headNo);
    }

    // ---------- 判定明细 ----------

    public void insertJudgment(long batchId, String headNo, String category, boolean highTemp,
                               String circulationArea, String createdAt) {
        jdbc.update("INSERT INTO judgment_detail(batch_id, head_no, category, high_temp,"
                        + " circulation_area, created_at) VALUES (?,?,?,?,?,?)",
                batchId, headNo, category, highTemp, circulationArea, createdAt);
    }

    public List<JudgmentRow> findJudgments(long batchId) {
        return jdbc.query("SELECT * FROM judgment_detail WHERE batch_id = ? ORDER BY head_no",
                JUDGMENT_MAPPER, batchId);
    }

    // ---------- 无害化处理回执 ----------

    public void insertReceipt(long batchId, String receiptNo, String category, int headCount,
                              String processor, boolean late, String receivedAt) {
        jdbc.update("INSERT INTO harmless_receipt(batch_id, receipt_no, category, head_count,"
                        + " processor, late, received_at) VALUES (?,?,?,?,?,?,?)",
                batchId, receiptNo, category, headCount, processor, late, receivedAt);
    }

    public List<ReceiptRow> findReceipts(long batchId) {
        return jdbc.query("SELECT * FROM harmless_receipt WHERE batch_id = ? ORDER BY id",
                RECEIPT_MAPPER, batchId);
    }

    // ---------- 迟到事件 ----------

    public void insertLateEvent(long batchId, String eventType, String detail, String createdAt) {
        jdbc.update("INSERT INTO late_event(batch_id, event_type, detail, created_at) VALUES (?,?,?,?)",
                batchId, eventType, detail, createdAt);
    }

    public List<LateEventRow> findLateEvents(long batchId) {
        return jdbc.query("SELECT * FROM late_event WHERE batch_id = ? ORDER BY id",
                LATE_EVENT_MAPPER, batchId);
    }

    // ---------- 幂等登记 ----------

    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?",
                IDEMPOTENCY_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(String requestId, String endpoint, String fingerprint,
                                  String responseBody, String createdAt) {
        jdbc.update("INSERT INTO idempotency_record(request_id, endpoint, fingerprint, response_body,"
                        + " created_at) VALUES (?,?,?,?,?)",
                requestId, endpoint, fingerprint, responseBody, createdAt);
    }
}
