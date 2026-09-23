package com.example.starter.evidence.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.evidence.domain.PurgeBatchStatus;
import com.example.starter.evidence.domain.PurgeItemStatus;

/**
 * 清除批次与候选清单的数据访问。
 */
@Repository
public class PurgeRepository {

    private final JdbcTemplate jdbc;

    public PurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按标记请求幂等键查询批次。
     */
    public Optional<PurgeBatchRow> findBatchByRequestId(String requestId) {
        List<PurgeBatchRow> rows = jdbc.query(
                "SELECT * FROM purge_batch WHERE request_id = ?",
                this::mapBatch, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询批次。
     */
    public Optional<PurgeBatchRow> findBatchById(String batchId) {
        List<PurgeBatchRow> rows = jdbc.query(
                "SELECT * FROM purge_batch WHERE batch_id = ?",
                this::mapBatch, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键加行锁查询批次，供最终确认串行化。
     */
    public Optional<PurgeBatchRow> findBatchByIdForUpdate(String batchId) {
        List<PurgeBatchRow> rows = jdbc.query(
                "SELECT * FROM purge_batch WHERE batch_id = ? FOR UPDATE",
                this::mapBatch, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 创建标记批次，初始状态 MARKED、版本 1。
     */
    public void insertBatch(String batchId, String requestId, String fingerprint, Instant cutoff,
            Long expectedVersion, Instant now) {
        jdbc.update(
                "INSERT INTO purge_batch (batch_id, request_id, fingerprint, cutoff, expected_version, status,"
                        + " version, created_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                batchId, requestId, fingerprint, Timestamp.from(cutoff), expectedVersion,
                PurgeBatchStatus.MARKED.name(), Timestamp.from(now));
    }

    /**
     * 最终确认批次：冻结清单、状态置为 CONFIRMED 并递增版本。
     */
    public void confirmBatch(String batchId, String manifestJson, String confirmRequestId,
            String confirmFingerprint, Instant now) {
        jdbc.update(
                "UPDATE purge_batch SET status = ?, version = version + 1, manifest_json = ?,"
                        + " confirm_request_id = ?, confirm_fingerprint = ?, confirmed_at = ? WHERE batch_id = ?",
                PurgeBatchStatus.CONFIRMED.name(), manifestJson, confirmRequestId, confirmFingerprint,
                Timestamp.from(now), batchId);
    }

    /**
     * 写入批次候选项，最终结果待确认。
     */
    public void insertItem(String batchId, String evidenceId) {
        jdbc.update("INSERT INTO purge_batch_item (batch_id, evidence_id) VALUES (?, ?)", batchId, evidenceId);
    }

    /**
     * 查询批次候选项，按证据标识排序返回。
     */
    public List<PurgeItemRow> findItems(String batchId) {
        return jdbc.query(
                "SELECT * FROM purge_batch_item WHERE batch_id = ? ORDER BY evidence_id",
                this::mapItem, batchId);
    }

    /**
     * 记录候选项最终结果。
     */
    public void updateItemFinal(String batchId, String evidenceId, PurgeItemStatus finalStatus, String reason) {
        jdbc.update(
                "UPDATE purge_batch_item SET final_status = ?, reason = ? WHERE batch_id = ? AND evidence_id = ?",
                finalStatus.name(), reason, batchId, evidenceId);
    }

    /**
     * 将指定证据在所有未确认批次中的待决候选项原子标记为救回，供新保全生效时调用。
     */
    public int rescuePendingItems(String evidenceId, String reason) {
        return jdbc.update(
                "UPDATE purge_batch_item SET final_status = ?, reason = ? WHERE evidence_id = ?"
                        + " AND final_status IS NULL AND batch_id IN (SELECT batch_id FROM purge_batch WHERE status = ?)",
                PurgeItemStatus.RESCUED.name(), reason, evidenceId, PurgeBatchStatus.MARKED.name());
    }

    private PurgeBatchRow mapBatch(ResultSet rs, int rowNum) throws SQLException {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        long expectedVersion = rs.getLong("expected_version");
        return new PurgeBatchRow(
                rs.getString("batch_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getTimestamp("cutoff").toInstant(),
                rs.wasNull() ? null : expectedVersion,
                PurgeBatchStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getString("manifest_json"),
                rs.getString("confirm_request_id"),
                rs.getString("confirm_fingerprint"),
                rs.getTimestamp("created_at").toInstant(),
                confirmedAt == null ? null : confirmedAt.toInstant());
    }

    private PurgeItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        String finalStatus = rs.getString("final_status");
        return new PurgeItemRow(
                rs.getString("batch_id"),
                rs.getString("evidence_id"),
                finalStatus == null ? null : PurgeItemStatus.valueOf(finalStatus),
                rs.getString("reason"));
    }
}
