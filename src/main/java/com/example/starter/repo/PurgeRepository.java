package com.example.starter.repo;

import com.example.starter.domain.PurgeBatchItem;
import com.example.starter.domain.PurgeManifestEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 清除批次、候选清单与不可变清除清单数据访问。
 */
@Repository
public class PurgeRepository {

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getString("batch_id"),
            rs.getString("mark_request_id"),
            rs.getTimestamp("cutoff_time").toInstant(),
            (Long) rs.getObject("expected_version"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("finalized_at") == null ? null : rs.getTimestamp("finalized_at").toInstant());

    private static final RowMapper<PurgeBatchItem> ITEM_MAPPER = (rs, n) -> new PurgeBatchItem(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getString("evidence_id"),
            "Y".equals(rs.getString("rescued")));

    private static final RowMapper<PurgeManifestEntry> MANIFEST_MAPPER = (rs, n) -> new PurgeManifestEntry(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getString("evidence_id"),
            rs.getString("sha256"),
            rs.getTimestamp("purged_at").toInstant());

    private final JdbcTemplate jdbc;

    public PurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子写入 MARKED 批次行及候选清单（须在调用方事务内）。
     */
    public void insertBatch(String batchId, String markRequestId, Instant cutoffTime,
                            Long expectedVersion, Instant now, List<String> candidateIds) {
        jdbc.update(
                "INSERT INTO purge_batch (batch_id, mark_request_id, cutoff_time, expected_version, "
                        + "status, created_at) VALUES (?, ?, ?, ?, 'MARKED', ?)",
                batchId, markRequestId, Timestamp.from(cutoffTime),
                expectedVersion == null ? null : expectedVersion.longValue(),
                Timestamp.from(now));
        for (String evidenceId : candidateIds) {
            jdbc.update(
                    "INSERT INTO purge_batch_item (batch_id, evidence_id, rescued) VALUES (?, ?, 'N')",
                    batchId, evidenceId);
        }
    }

    public Optional<BatchRow> findBatch(String batchId) {
        return queryOne(
                "SELECT batch_id, mark_request_id, cutoff_time, expected_version, status, created_at, finalized_at "
                        + "FROM purge_batch WHERE batch_id = ?",
                batchId);
    }

    public Optional<BatchRow> findBatchByMarkRequestId(String markRequestId) {
        return queryOne(
                "SELECT batch_id, mark_request_id, cutoff_time, expected_version, status, created_at, finalized_at "
                        + "FROM purge_batch WHERE mark_request_id = ?",
                markRequestId);
    }

    /**
     * 行锁锁定批次行，串行化同一批次的并发最终确认。
     */
    public Optional<BatchRow> lockBatch(String batchId) {
        return queryOne(
                "SELECT batch_id, mark_request_id, cutoff_time, expected_version, status, created_at, finalized_at "
                        + "FROM purge_batch WHERE batch_id = ? FOR UPDATE",
                batchId);
    }

    public List<PurgeBatchItem> listItems(String batchId) {
        return jdbc.query(
                "SELECT id, batch_id, evidence_id, rescued FROM purge_batch_item "
                        + "WHERE batch_id = ? ORDER BY evidence_id",
                ITEM_MAPPER, batchId);
    }

    public int markRescued(String batchId, List<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                "UPDATE purge_batch_item SET rescued = 'Y' WHERE batch_id = ? AND rescued = 'N' "
                        + "AND evidence_id IN (" + placeholders(evidenceIds.size()) + ")",
                mergeArgs(batchId, evidenceIds));
    }

    public void insertManifestEntry(String batchId, String evidenceId, String sha256, Instant purgedAt) {
        jdbc.update(
                "INSERT INTO purge_manifest (batch_id, evidence_id, sha256, purged_at) VALUES (?, ?, ?, ?)",
                batchId, evidenceId, sha256, Timestamp.from(purgedAt));
    }

    /**
     * 批次状态 MARKED -> PURGED 的条件更新，返回影响行数。
     */
    public int finalizeBatch(String batchId, Instant finalizedAt) {
        return jdbc.update(
                "UPDATE purge_batch SET status = 'PURGED', finalized_at = ? WHERE batch_id = ? AND status = 'MARKED'",
                Timestamp.from(finalizedAt), batchId);
    }

    public List<PurgeManifestEntry> listManifest(String batchId) {
        return jdbc.query(
                "SELECT id, batch_id, evidence_id, sha256, purged_at FROM purge_manifest "
                        + "WHERE batch_id = ? ORDER BY evidence_id",
                MANIFEST_MAPPER, batchId);
    }

    private Optional<BatchRow> queryOne(String sql, String arg) {
        List<BatchRow> rows = jdbc.query(sql, BATCH_MAPPER, arg);
        return rows.stream().findFirst();
    }

    private static Object[] mergeArgs(String batchId, List<String> evidenceIds) {
        Object[] args = new Object[evidenceIds.size() + 1];
        args[0] = batchId;
        for (int i = 0; i < evidenceIds.size(); i++) {
            args[i + 1] = evidenceIds.get(i);
        }
        return args;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * purge_batch 行投影。
     */
    public record BatchRow(
            String batchId,
            String markRequestId,
            Instant cutoffTime,
            Long expectedVersion,
            String status,
            Instant createdAt,
            Instant finalizedAt
    ) {
    }
}
