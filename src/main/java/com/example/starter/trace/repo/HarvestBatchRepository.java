package com.example.starter.trace.repo;

import com.example.starter.trace.BatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 采收批及其蜂场贡献的数据访问。
 */
@Repository
public class HarvestBatchRepository {

    private final JdbcTemplate jdbc;

    public HarvestBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入采收批并返回自增 ID。
     */
    public long insertBatch(String batchNo, String flowerPeriod, BatchStatus status, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO harvest_batch (batch_no, flower_period, status, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, flowerPeriod);
            ps.setString(3, status.name());
            ps.setTimestamp(4, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 插入一条蜂场贡献明细。
     */
    public void insertContribution(long batchId, long apiaryId, BigDecimal ratio) {
        jdbc.update(
                "INSERT INTO harvest_batch_contribution (batch_id, apiary_id, ratio) VALUES (?, ?, ?)",
                batchId, apiaryId, ratio);
    }

    /**
     * 按 ID 查询采收批。
     */
    public Optional<BatchRow> findById(long batchId) {
        return jdbc.query(
                        "SELECT id, batch_no, flower_period, status, created_at FROM harvest_batch WHERE id = ?",
                        (rs, i) -> mapBatch(rs.getLong("id"), rs.getString("batch_no"),
                                rs.getString("flower_period"), rs.getString("status"),
                                rs.getTimestamp("created_at")),
                        batchId)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁，用于同事务内的状态校验与变更。
     */
    public Optional<BatchRow> findByIdForUpdate(long batchId) {
        return jdbc.query(
                        "SELECT id, batch_no, flower_period, status, created_at FROM harvest_batch WHERE id = ? FOR UPDATE",
                        (rs, i) -> mapBatch(rs.getLong("id"), rs.getString("batch_no"),
                                rs.getString("flower_period"), rs.getString("status"),
                                rs.getTimestamp("created_at")),
                        batchId)
                .stream().findFirst();
    }

    /**
     * 更新采收批状态。
     */
    public void updateStatus(long batchId, BatchStatus status) {
        jdbc.update("UPDATE harvest_batch SET status = ? WHERE id = ?", status.name(), batchId);
    }

    /**
     * 查询采收批的全部贡献明细。
     */
    public List<ContributionRow> findContributions(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, apiary_id, ratio FROM harvest_batch_contribution WHERE batch_id = ? ORDER BY id",
                (rs, i) -> new ContributionRow(rs.getLong("id"), rs.getLong("batch_id"),
                        rs.getLong("apiary_id"), rs.getBigDecimal("ratio")),
                batchId);
    }

    private BatchRow mapBatch(long id, String batchNo, String flowerPeriod, String status, Timestamp createdAt) {
        return new BatchRow(id, batchNo, flowerPeriod, BatchStatus.valueOf(status), createdAt.toLocalDateTime());
    }

    /**
     * 采收批行记录。
     */
    public record BatchRow(Long id, String batchNo, String flowerPeriod, BatchStatus status,
                           LocalDateTime createdAt) {
    }

    /**
     * 贡献明细行记录。
     */
    public record ContributionRow(Long id, Long batchId, Long apiaryId, BigDecimal ratio) {
    }
}
