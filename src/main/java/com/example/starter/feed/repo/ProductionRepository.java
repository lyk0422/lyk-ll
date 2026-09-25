package com.example.starter.feed.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 投产记录及投产时刻药物快照仓储；另含饲喂事件登记。
 */
@Repository
public class ProductionRepository {

    private final JdbcTemplate jdbc;
    private final org.springframework.jdbc.core.simple.SimpleJdbcInsert feedingInsert;

    public ProductionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.feedingInsert = new org.springframework.jdbc.core.simple.SimpleJdbcInsert(jdbc)
                .withTableName("feeding_record")
                .usingColumns("batch_id", "production_id", "formula_code", "version", "feed_at_utc", "late")
                .usingGeneratedKeyColumns("id");
    }

    /** production_record 行。 */
    public record ProductionRow(
            long id,
            long batchId,
            long formulaId,
            String formulaCode,
            int version,
            Instant producedAtUtc
    ) {
    }

    /** 投产药物快照行。 */
    public record DrugSnapshotRow(String drugCode, BigDecimal ratio, int withdrawalDays) {
    }

    /** feeding_record 行。 */
    public record FeedingRow(
            long id,
            long batchId,
            long productionId,
            String formulaCode,
            int version,
            Instant feedAtUtc,
            boolean late
    ) {
    }

    private static final RowMapper<ProductionRow> PRODUCTION_MAPPER = (rs, n) -> new ProductionRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("formula_id"),
            rs.getString("formula_code"),
            rs.getInt("version"),
            DictRepository.toInstant(rs.getTimestamp("produced_at_utc"))
    );

    private static final RowMapper<DrugSnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new DrugSnapshotRow(
            rs.getString("drug_code"), rs.getBigDecimal("ratio"), rs.getInt("withdrawal_days"));

    private static final RowMapper<FeedingRow> FEEDING_MAPPER = (rs, n) -> new FeedingRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("production_id"),
            rs.getString("formula_code"),
            rs.getInt("version"),
            DictRepository.toInstant(rs.getTimestamp("feed_at_utc")),
            rs.getBoolean("late")
    );

    /**
     * 插入投产记录；同批次同配方版本重复投产触发唯一约束。
     *
     * @return 新投产记录 id；重复返回空
     */
    public Optional<Long> insertProduction(long batchId, long formulaId, String formulaCode, int version,
                                           Instant producedAtUtc) {
        try {
            jdbc.update("INSERT INTO production_record (batch_id, formula_id, formula_code, version, produced_at_utc) "
                            + "VALUES (?,?,?,?,?)",
                    batchId, formulaId, formulaCode, version, Timestamp.from(producedAtUtc));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id FROM production_record WHERE batch_id = ? AND formula_id = ?",
                        (rs, n) -> rs.getLong("id"), batchId, formulaId)
                .stream().findFirst();
    }

    public void insertDrugSnapshot(long productionId, String drugCode, BigDecimal ratio, int withdrawalDays) {
        jdbc.update("INSERT INTO production_drug_snapshot (production_id, drug_code, ratio, withdrawal_days) "
                        + "VALUES (?,?,?,?)",
                productionId, drugCode, ratio, withdrawalDays);
    }

    public List<ProductionRow> findByBatch(long batchId) {
        return jdbc.query("SELECT * FROM production_record WHERE batch_id = ? ORDER BY id",
                PRODUCTION_MAPPER, batchId);
    }

    public Optional<ProductionRow> findByBatchAndFormula(long batchId, long formulaId) {
        return jdbc.query("SELECT * FROM production_record WHERE batch_id = ? AND formula_id = ?",
                        PRODUCTION_MAPPER, batchId, formulaId)
                .stream().findFirst();
    }

    public List<DrugSnapshotRow> findSnapshots(long productionId) {
        return jdbc.query("SELECT drug_code, ratio, withdrawal_days FROM production_drug_snapshot "
                + "WHERE production_id = ? ORDER BY id", SNAPSHOT_MAPPER, productionId);
    }

    /**
     * 取批次全部投产快照（用于按药物逐支复算合并休药期）。
     */
    public List<DrugSnapshotRow> findAllSnapshotsByBatch(long batchId) {
        return jdbc.query(
                "SELECT s.drug_code, s.ratio, s.withdrawal_days FROM production_drug_snapshot s "
                        + "JOIN production_record p ON p.id = s.production_id "
                        + "WHERE p.batch_id = ? ORDER BY s.id",
                SNAPSHOT_MAPPER, batchId);
    }

    public long insertFeeding(long batchId, long productionId, String formulaCode, int version,
                              Instant feedAtUtc, boolean late) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("batch_id", batchId);
        params.put("production_id", productionId);
        params.put("formula_code", formulaCode);
        params.put("version", version);
        params.put("feed_at_utc", Timestamp.from(feedAtUtc));
        params.put("late", late);
        return feedingInsert.executeAndReturnKey(params).longValue();
    }

    public void insertFeedingDrug(long feedingId, String drugCode, int withdrawalDays) {
        jdbc.update("INSERT INTO feeding_record_drug (feeding_id, drug_code, withdrawal_days) VALUES (?,?,?)",
                feedingId, drugCode, withdrawalDays);
    }

    public List<FeedingRow> findFeedingsByBatch(long batchId) {
        return jdbc.query("SELECT * FROM feeding_record WHERE batch_id = ? ORDER BY id",
                FEEDING_MAPPER, batchId);
    }
}
