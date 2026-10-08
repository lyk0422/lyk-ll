package com.example.starter.trace.repo;

import com.example.starter.trace.RecallStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 来源召回单、活动占用、隔离关联与加工批隔离标记的数据访问。
 */
@Repository
public class SourceRecallRepository {

    private final JdbcTemplate jdbc;

    public SourceRecallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入召回单并返回自增 ID，初始状态 ACTIVE、版本 1。
     */
    public long insertRecall(long apiaryId, String flowerPeriod, String reason, String credential,
                             String inspector, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO source_recall (apiary_id, flower_period, reason, credential, inspector,"
                            + " status, version, created_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, apiaryId);
            ps.setString(2, flowerPeriod);
            ps.setString(3, reason);
            ps.setString(4, credential);
            ps.setString(5, inspector);
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 占用同蜂场同花期的活动召回槽位。
     *
     * @return true 占用成功；false 已存在活动召回
     */
    public boolean tryOccupyActiveSlot(long apiaryId, String flowerPeriod, long recallId) {
        try {
            jdbc.update("INSERT INTO active_source_recall (apiary_id, flower_period, recall_id) VALUES (?, ?, ?)",
                    apiaryId, flowerPeriod, recallId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 释放同蜂场同花期的活动召回槽位（撤回时调用）。
     */
    public void releaseActiveSlot(long apiaryId, String flowerPeriod) {
        jdbc.update("DELETE FROM active_source_recall WHERE apiary_id = ? AND flower_period = ?",
                apiaryId, flowerPeriod);
    }

    /**
     * 查询指定蜂场与花期当前的活动召回单 ID。
     */
    public Optional<Long> findActiveRecallId(long apiaryId, String flowerPeriod) {
        return jdbc.query("SELECT recall_id FROM active_source_recall WHERE apiary_id = ? AND flower_period = ?",
                        (rs, i) -> rs.getLong("recall_id"), apiaryId, flowerPeriod)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询召回单。
     */
    public Optional<RecallRow> findById(long recallId) {
        return jdbc.query(
                        "SELECT id, apiary_id, flower_period, reason, status, version, created_at, withdrawn_at"
                                + " FROM source_recall WHERE id = ?",
                        (rs, i) -> mapRecall(rs.getLong("id"), rs.getLong("apiary_id"),
                                rs.getString("flower_period"), rs.getString("reason"),
                                rs.getString("status"), rs.getInt("version"),
                                rs.getTimestamp("created_at"), rs.getTimestamp("withdrawn_at")),
                        recallId)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁，用于撤回时的版本校验与状态变更。
     */
    public Optional<RecallRow> findByIdForUpdate(long recallId) {
        return jdbc.query(
                        "SELECT id, apiary_id, flower_period, reason, status, version, created_at, withdrawn_at"
                                + " FROM source_recall WHERE id = ? FOR UPDATE",
                        (rs, i) -> mapRecall(rs.getLong("id"), rs.getLong("apiary_id"),
                                rs.getString("flower_period"), rs.getString("reason"),
                                rs.getString("status"), rs.getInt("version"),
                                rs.getTimestamp("created_at"), rs.getTimestamp("withdrawn_at")),
                        recallId)
                .stream().findFirst();
    }

    /**
     * 撤回召回单：状态置 WITHDRAWN、版本加 1、记录撤回时间（UTC）。
     */
    public void markWithdrawn(long recallId, LocalDateTime withdrawnAt) {
        jdbc.update("UPDATE source_recall SET status = 'WITHDRAWN', version = version + 1,"
                        + " withdrawn_at = ? WHERE id = ?",
                Timestamp.valueOf(withdrawnAt), recallId);
    }

    /**
     * 查询含指定来源（蜂场 + 精确花期）正贡献的全部采收批 ID，按 ID 升序。
     */
    public List<Long> findBatchIdsBySource(long apiaryId, String flowerPeriod) {
        return jdbc.query(
                "SELECT DISTINCT b.id FROM harvest_batch b"
                        + " JOIN harvest_batch_contribution c ON c.batch_id = b.id"
                        + " WHERE c.apiary_id = ? AND b.flower_period = ? AND c.ratio > 0 ORDER BY b.id",
                (rs, i) -> rs.getLong("id"), apiaryId, flowerPeriod);
    }

    /**
     * 建立一条召回隔离关联。
     *
     * @param initialSnapshot true 属于创建时首次冻结清单；false 后来登记批次追加
     */
    public void insertLink(long recallId, long harvestBatchId, boolean initialSnapshot,
                           LocalDateTime createdAt) {
        jdbc.update("INSERT INTO source_recall_link (recall_id, harvest_batch_id, initial_snapshot, status, created_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                recallId, harvestBatchId, initialSnapshot, Timestamp.valueOf(createdAt));
    }

    /**
     * 使指定召回的全部隔离关联失效（撤回时调用，不影响其他召回的关联）。
     */
    public void releaseLinks(long recallId) {
        jdbc.update("UPDATE source_recall_link SET status = 'RELEASED' WHERE recall_id = ? AND status = 'ACTIVE'",
                recallId);
    }

    /**
     * 判断采收批是否处于任一活动召回的隔离中。
     */
    public boolean existsActiveLinkForBatch(long harvestBatchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_recall_link WHERE harvest_batch_id = ? AND status = 'ACTIVE'",
                Integer.class, harvestBatchId);
        return count != null && count > 0;
    }

    /**
     * 查询召回的全部隔离关联（含已失效），按关联 ID 升序。
     */
    public List<LinkRow> findLinks(long recallId) {
        return jdbc.query(
                "SELECT l.id, l.harvest_batch_id, b.batch_no, l.initial_snapshot, l.status, l.created_at"
                        + " FROM source_recall_link l JOIN harvest_batch b ON b.id = l.harvest_batch_id"
                        + " WHERE l.recall_id = ? ORDER BY l.id",
                (rs, i) -> new LinkRow(rs.getLong("id"), rs.getLong("harvest_batch_id"),
                        rs.getString("batch_no"), rs.getBoolean("initial_snapshot"),
                        rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()),
                recallId);
    }

    /**
     * 为加工批加召回隔离标记。
     */
    public void insertMark(long recallId, long processingBatchId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO processing_recall_mark (recall_id, processing_batch_id, status, created_at)"
                        + " VALUES (?, ?, 'ACTIVE', ?)",
                recallId, processingBatchId, Timestamp.valueOf(createdAt));
    }

    /**
     * 使指定召回的全部加工批隔离标记失效（撤回时调用）。
     */
    public void releaseMarks(long recallId) {
        jdbc.update("UPDATE processing_recall_mark SET status = 'RELEASED' WHERE recall_id = ? AND status = 'ACTIVE'",
                recallId);
    }

    /**
     * 查询召回的全部加工批隔离标记（含已失效），按标记 ID 升序。
     */
    public List<MarkRow> findMarks(long recallId) {
        return jdbc.query(
                "SELECT m.id, m.processing_batch_id, p.batch_no, m.status, m.created_at"
                        + " FROM processing_recall_mark m JOIN processing_batch p ON p.id = m.processing_batch_id"
                        + " WHERE m.recall_id = ? ORDER BY m.id",
                (rs, i) -> new MarkRow(rs.getLong("id"), rs.getLong("processing_batch_id"),
                        rs.getString("batch_no"), rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                recallId);
    }

    private RecallRow mapRecall(long id, long apiaryId, String flowerPeriod, String reason,
                                String status, int version, Timestamp createdAt, Timestamp withdrawnAt) {
        return new RecallRow(id, apiaryId, flowerPeriod, reason, RecallStatus.valueOf(status), version,
                createdAt.toLocalDateTime(), withdrawnAt == null ? null : withdrawnAt.toLocalDateTime());
    }

    /**
     * 召回单行记录，时间字段为 UTC。
     */
    public record RecallRow(Long id, Long apiaryId, String flowerPeriod, String reason,
                            RecallStatus status, Integer version,
                            LocalDateTime createdAt, LocalDateTime withdrawnAt) {
    }

    /**
     * 隔离关联行记录。
     */
    public record LinkRow(Long id, Long harvestBatchId, String batchNo, Boolean initialSnapshot,
                          String status, LocalDateTime createdAt) {
    }

    /**
     * 加工批隔离标记行记录。
     */
    public record MarkRow(Long id, Long processingBatchId, String processingBatchNo,
                          String status, LocalDateTime createdAt) {
    }
}
