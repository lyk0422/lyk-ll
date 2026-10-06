package com.example.starter.trace.repo;

import com.example.starter.trace.api.TraceDtos.ContributionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 采收批与蜂场贡献的数据访问。
 */
@Repository
public class HarvestRepository {

    /** 采收批行记录。 */
    public record HarvestBatchRow(
            Long id,
            String batchNo,
            String floralPeriod,
            String status,
            LocalDateTime createdAt) {
    }

    private static final RowMapper<HarvestBatchRow> BATCH_MAPPER = (rs, n) -> new HarvestBatchRow(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getString("floral_period"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<ContributionView> CONTRIBUTION_MAPPER = (rs, n) -> new ContributionView(
            rs.getLong("apiary_id"),
            rs.getString("code"),
            rs.getBigDecimal("ratio"));

    private final JdbcTemplate jdbc;

    public HarvestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入采收批，初始状态 REGISTERED，返回自增主键。
     */
    public Long insertBatch(String batchNo, String floralPeriod, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO harvest_batch (batch_no, floral_period, status, created_at) VALUES (?, ?, 'REGISTERED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, floralPeriod);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 插入一条蜂场贡献明细。
     */
    public void insertContribution(Long batchId, Long apiaryId, java.math.BigDecimal ratio) {
        jdbc.update("INSERT INTO harvest_contribution (batch_id, apiary_id, ratio) VALUES (?, ?, ?)",
                batchId, apiaryId, ratio);
    }

    /**
     * 按主键查询采收批。
     */
    public Optional<HarvestBatchRow> findBatchById(Long id) {
        List<HarvestBatchRow> rows = jdbc.query(
                "SELECT id, batch_no, floral_period, status, created_at FROM harvest_batch WHERE id = ?",
                BATCH_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询并加行级写锁（SELECT ... FOR UPDATE），用于同事务内串行化状态变更。
     */
    public Optional<HarvestBatchRow> lockBatchById(Long id) {
        List<HarvestBatchRow> rows = jdbc.query(
                "SELECT id, batch_no, floral_period, status, created_at FROM harvest_batch WHERE id = ? FOR UPDATE",
                BATCH_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 更新采收批状态。
     */
    public void updateBatchStatus(Long batchId, String status) {
        jdbc.update("UPDATE harvest_batch SET status = ? WHERE id = ?", status, batchId);
    }

    /**
     * 查询采收批的蜂场贡献明细（含蜂场编码）。
     */
    public List<ContributionView> findContributions(Long batchId) {
        return jdbc.query(
                "SELECT c.apiary_id, a.code, c.ratio FROM harvest_contribution c "
                        + "JOIN apiary a ON a.id = c.apiary_id WHERE c.batch_id = ? ORDER BY c.id",
                CONTRIBUTION_MAPPER, batchId);
    }

    /**
     * 判断蜂场是否存在。
     */
    public boolean apiaryExists(Long apiaryId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM apiary WHERE id = ?", Integer.class, apiaryId);
        return count != null && count > 0;
    }
}
