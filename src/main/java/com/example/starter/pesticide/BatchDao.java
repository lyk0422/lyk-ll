package com.example.starter.pesticide;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批次与抽样数据访问：所有时间字段为 UTC 墙钟时间。
 */
@Repository
public class BatchDao {

    /**
     * 批次行记录。
     */
    public record BatchRow(
            long id,
            String requestId,
            String fingerprint,
            String origin,
            String variety,
            String batchNo,
            BigDecimal quantity,
            String status,
            String samplingLevel,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 抽样行记录。
     */
    public record SampleRow(
            long id,
            long batchId,
            String item,
            LocalDateTime createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("origin"),
            rs.getString("variety"),
            rs.getString("batch_no"),
            rs.getBigDecimal("quantity"),
            rs.getString("status"),
            rs.getString("sampling_level"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("item"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public BatchDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入批次，返回生成主键；唯一约束冲突时抛出 DuplicateKeyException。
     */
    public long insertBatch(String requestId, String fingerprint, String origin, String variety,
                            String batchNo, BigDecimal quantity, String status, String samplingLevel,
                            LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch (request_id, fingerprint, origin, variety, batch_no, quantity,"
                            + " status, sampling_level, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, origin);
            ps.setString(4, variety);
            ps.setString(5, batchNo);
            ps.setBigDecimal(6, quantity);
            ps.setString(7, status);
            ps.setString(8, samplingLevel);
            ps.setTimestamp(9, Timestamp.valueOf(now));
            ps.setTimestamp(10, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询批次。
     */
    public Optional<BatchRow> findByRequestId(String requestId) {
        List<BatchRow> rows = jdbc.query("SELECT * FROM batch WHERE request_id = ?",
                BATCH_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询批次。
     */
    public Optional<BatchRow> findById(long id) {
        List<BatchRow> rows = jdbc.query("SELECT * FROM batch WHERE id = ?", BATCH_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键行级锁定批次（SELECT FOR UPDATE），须在事务内调用。
     */
    public Optional<BatchRow> lockById(long id) {
        List<BatchRow> rows = jdbc.query("SELECT * FROM batch WHERE id = ? FOR UPDATE", BATCH_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 更新批次状态与变更时间。
     */
    public void updateStatus(long id, String status, LocalDateTime now) {
        jdbc.update("UPDATE batch SET status = ?, updated_at = ? WHERE id = ?",
                status, Timestamp.valueOf(now), id);
    }

    /**
     * 插入抽样记录；批次+项目唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertSample(long batchId, String item, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sample (batch_id, item, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, item);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 判断批次+项目是否已抽样。
     */
    public boolean sampleExists(long batchId, String item) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample WHERE batch_id = ? AND item = ?",
                Integer.class, batchId, item);
        return count != null && count > 0;
    }

    /**
     * 查询批次全部抽样记录。
     */
    public List<SampleRow> findSamples(long batchId) {
        return jdbc.query("SELECT * FROM sample WHERE batch_id = ? ORDER BY id", SAMPLE_MAPPER, batchId);
    }
}
