package com.example.starter.trace.repo;

import com.example.starter.trace.RecallLinkOrigin;
import com.example.starter.trace.RecallLinkStatus;
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
 * 来源召回单及其隔离关联的数据访问。
 * 同蜂场同花期的 ACTIVE 唯一性由 active_key 唯一约束兜底。
 */
@Repository
public class RecallRepository {

    private final JdbcTemplate jdbc;

    public RecallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 活动召回唯一键：蜂场 ID 与花期的组合，仅 ACTIVE 召回占用。
     */
    public static String activeKey(long apiaryId, String flowerPeriod) {
        return apiaryId + "|" + flowerPeriod;
    }

    /**
     * 插入一条 ACTIVE 召回单，版本从 1 开始。
     *
     * @return 自增 ID
     * @throws DuplicateKeyException 同蜂场同花期已存在 ACTIVE 召回
     */
    public long insertRecall(long apiaryId, String flowerPeriod, String reason, String credential,
                             String createdBy, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO source_recall (apiary_id, flower_period, reason, credential, status, version,"
                            + " active_key, created_by, created_at) VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, apiaryId);
            ps.setString(2, flowerPeriod);
            ps.setString(3, reason);
            ps.setString(4, credential);
            ps.setString(5, activeKey(apiaryId, flowerPeriod));
            ps.setString(6, createdBy);
            ps.setTimestamp(7, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 按 ID 查询召回单。
     */
    public Optional<RecallRow> findById(long recallId) {
        return jdbc.query(
                        "SELECT id, apiary_id, flower_period, reason, credential, status, version, created_by,"
                                + " created_at, withdrawn_at FROM source_recall WHERE id = ?",
                        (rs, i) -> mapRecall(rs.getLong("id"), rs.getLong("apiary_id"),
                                rs.getString("flower_period"), rs.getString("reason"),
                                rs.getString("credential"), rs.getString("status"), rs.getInt("version"),
                                rs.getString("created_by"), rs.getTimestamp("created_at"),
                                rs.getTimestamp("withdrawn_at")),
                        recallId)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁，用于撤回时的版本校验与状态变更。
     */
    public Optional<RecallRow> findByIdForUpdate(long recallId) {
        return jdbc.query(
                        "SELECT id, apiary_id, flower_period, reason, credential, status, version, created_by,"
                                + " created_at, withdrawn_at FROM source_recall WHERE id = ? FOR UPDATE",
                        (rs, i) -> mapRecall(rs.getLong("id"), rs.getLong("apiary_id"),
                                rs.getString("flower_period"), rs.getString("reason"),
                                rs.getString("credential"), rs.getString("status"), rs.getInt("version"),
                                rs.getString("created_by"), rs.getTimestamp("created_at"),
                                rs.getTimestamp("withdrawn_at")),
                        recallId)
                .stream().findFirst();
    }

    /**
     * 查询同蜂场同花期当前 ACTIVE 的召回单。
     */
    public Optional<RecallRow> findActiveBySource(long apiaryId, String flowerPeriod) {
        return jdbc.query(
                        "SELECT id, apiary_id, flower_period, reason, credential, status, version, created_by,"
                                + " created_at, withdrawn_at FROM source_recall WHERE active_key = ?",
                        (rs, i) -> mapRecall(rs.getLong("id"), rs.getLong("apiary_id"),
                                rs.getString("flower_period"), rs.getString("reason"),
                                rs.getString("credential"), rs.getString("status"), rs.getInt("version"),
                                rs.getString("created_by"), rs.getTimestamp("created_at"),
                                rs.getTimestamp("withdrawn_at")),
                        activeKey(apiaryId, flowerPeriod))
                .stream().findFirst();
    }

    /**
     * 乐观锁撤回：仅当版本匹配且仍为 ACTIVE 时生效，版本加 1 并释放活动唯一键。
     *
     * @return true 撤回成功；false 版本过期或已撤回
     */
    public boolean withdraw(long recallId, long expectedVersion, String inspectorPrimary,
                            String inspectorSecondary, String credentialPrimary,
                            String credentialSecondary, LocalDateTime withdrawnAt) {
        int updated = jdbc.update(
                "UPDATE source_recall SET status = 'WITHDRAWN', version = version + 1, active_key = NULL,"
                        + " withdrawn_by_primary = ?, withdrawn_by_secondary = ?,"
                        + " withdraw_credential_primary = ?, withdraw_credential_secondary = ?,"
                        + " withdrawn_at = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                inspectorPrimary, inspectorSecondary, credentialPrimary, credentialSecondary,
                Timestamp.valueOf(withdrawnAt), recallId, expectedVersion);
        return updated == 1;
    }

    /**
     * 插入召回-采收批隔离关联。
     */
    public void insertHarvestLink(long recallId, long harvestBatchId, RecallLinkOrigin origin,
                                  LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO recall_harvest_link (recall_id, harvest_batch_id, link_origin, status, created_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                recallId, harvestBatchId, origin.name(), Timestamp.valueOf(createdAt));
    }

    /**
     * 插入召回-加工批隔离关联（仅隔离标记，不删原加工证据）。
     */
    public void insertProcessingLink(long recallId, long processingBatchId, RecallLinkOrigin origin,
                                     LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO recall_processing_link (recall_id, processing_batch_id, link_origin, status, created_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                recallId, processingBatchId, origin.name(), Timestamp.valueOf(createdAt));
    }

    /**
     * 查询召回单的全部采收批关联（含已失效），按 ID 升序。
     */
    public List<HarvestLinkRow> findHarvestLinks(long recallId) {
        return jdbc.query(
                "SELECT l.id, l.harvest_batch_id, b.batch_no, l.link_origin, l.status, l.created_at"
                        + " FROM recall_harvest_link l JOIN harvest_batch b ON b.id = l.harvest_batch_id"
                        + " WHERE l.recall_id = ? ORDER BY l.id",
                (rs, i) -> new HarvestLinkRow(rs.getLong("id"), rs.getLong("harvest_batch_id"),
                        rs.getString("batch_no"),
                        RecallLinkOrigin.valueOf(rs.getString("link_origin")),
                        RecallLinkStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                recallId);
    }

    /**
     * 查询召回单的全部加工批关联（含已失效），按 ID 升序。
     */
    public List<ProcessingLinkRow> findProcessingLinks(long recallId) {
        return jdbc.query(
                "SELECT l.id, l.processing_batch_id, p.batch_no, l.link_origin, l.status, l.created_at"
                        + " FROM recall_processing_link l JOIN processing_batch p ON p.id = l.processing_batch_id"
                        + " WHERE l.recall_id = ? ORDER BY l.id",
                (rs, i) -> new ProcessingLinkRow(rs.getLong("id"), rs.getLong("processing_batch_id"),
                        rs.getString("batch_no"),
                        RecallLinkOrigin.valueOf(rs.getString("link_origin")),
                        RecallLinkStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                recallId);
    }

    /**
     * 将召回单的全部活动关联置为失效；不影响其他召回的关联。
     */
    public void releaseLinks(long recallId) {
        jdbc.update("UPDATE recall_harvest_link SET status = 'RELEASED' WHERE recall_id = ? AND status = 'ACTIVE'",
                recallId);
        jdbc.update("UPDATE recall_processing_link SET status = 'RELEASED' WHERE recall_id = ? AND status = 'ACTIVE'",
                recallId);
    }

    /**
     * 统计采收批当前生效的隔离关联数（关联 ACTIVE 且所属召回 ACTIVE）。
     */
    public int countActiveHarvestLinks(long harvestBatchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall_harvest_link l"
                        + " JOIN source_recall r ON r.id = l.recall_id"
                        + " WHERE l.harvest_batch_id = ? AND l.status = 'ACTIVE' AND r.status = 'ACTIVE'",
                Integer.class, harvestBatchId);
        return count == null ? 0 : count;
    }

    /**
     * 查询含指定蜂场正贡献且花期精确匹配的采收批 ID，按 ID 升序。
     */
    public List<Long> findSourceHarvestBatchIds(long apiaryId, String flowerPeriod) {
        return jdbc.query(
                "SELECT DISTINCT b.id FROM harvest_batch b"
                        + " JOIN harvest_batch_contribution c ON c.batch_id = b.id"
                        + " WHERE c.apiary_id = ? AND c.ratio > 0 AND b.flower_period = ? ORDER BY b.id",
                (rs, i) -> rs.getLong("id"), apiaryId, flowerPeriod);
    }

    /**
     * 查询引用了指定采收批的加工批次 ID，按 ID 升序。
     */
    public List<Long> findProcessingBatchIdsByHarvestBatch(long harvestBatchId) {
        return jdbc.query(
                "SELECT processing_batch_id FROM processing_batch_ref WHERE harvest_batch_id = ?"
                        + " ORDER BY processing_batch_id",
                (rs, i) -> rs.getLong("processing_batch_id"), harvestBatchId);
    }

    private RecallRow mapRecall(long id, long apiaryId, String flowerPeriod, String reason, String credential,
                                String status, int version, String createdBy,
                                Timestamp createdAt, Timestamp withdrawnAt) {
        return new RecallRow(id, apiaryId, flowerPeriod, reason, credential, RecallStatus.valueOf(status),
                version, createdBy, createdAt.toLocalDateTime(),
                withdrawnAt == null ? null : withdrawnAt.toLocalDateTime());
    }

    /**
     * 召回单行记录。
     */
    public record RecallRow(Long id, Long apiaryId, String flowerPeriod, String reason, String credential,
                            RecallStatus status, Integer version, String createdBy,
                            LocalDateTime createdAt, LocalDateTime withdrawnAt) {
    }

    /**
     * 召回-采收批关联行记录。
     */
    public record HarvestLinkRow(Long id, Long harvestBatchId, String batchNo, RecallLinkOrigin origin,
                                 RecallLinkStatus status, LocalDateTime createdAt) {
    }

    /**
     * 召回-加工批关联行记录。
     */
    public record ProcessingLinkRow(Long id, Long processingBatchId, String batchNo, RecallLinkOrigin origin,
                                    RecallLinkStatus status, LocalDateTime createdAt) {
    }
}
