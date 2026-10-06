package com.example.starter.trace.repo;

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

/**
 * 加工批次及其对采收批引用的数据访问。
 */
@Repository
public class ProcessingBatchRepository {

    private final JdbcTemplate jdbc;

    public ProcessingBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入加工批次并返回自增 ID。
     */
    public long insertProcessingBatch(String batchNo, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO processing_batch (batch_no, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setTimestamp(2, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 插入加工批次对采收批的引用。
     */
    public void insertRef(long processingBatchId, long harvestBatchId) {
        jdbc.update(
                "INSERT INTO processing_batch_ref (processing_batch_id, harvest_batch_id) VALUES (?, ?)",
                processingBatchId, harvestBatchId);
    }

    /**
     * 查询加工批次引用的采收批 ID，按 ID 升序。
     */
    public List<Long> findRefHarvestBatchIds(long processingBatchId) {
        return jdbc.query(
                "SELECT harvest_batch_id FROM processing_batch_ref WHERE processing_batch_id = ? ORDER BY id",
                (rs, i) -> rs.getLong("harvest_batch_id"), processingBatchId);
    }
}
