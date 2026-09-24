package com.example.starter.ili.persistence;

import com.example.starter.ili.model.BatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 对齐批次数据访问：插入、按幂等键查询、行级锁查询与封板状态迁移。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<BatchRow> MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("segment_id"),
            rs.getString("old_run_id"),
            rs.getString("new_run_id"),
            rs.getDate("old_inspected_at").toLocalDate(),
            rs.getDate("new_inspected_at").toLocalDate(),
            rs.getString("fingerprint"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("sealed_at") == null ? null : rs.getTimestamp("sealed_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新批次（OPEN 状态），返回生成主键。
     */
    public long insert(String requestId, String segmentId, String oldRunId, String newRunId,
                       LocalDate oldInspectedAt, LocalDate newInspectedAt, String fingerprint) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO alignment_batch (request_id, segment_id, old_run_id, new_run_id,"
                            + " old_inspected_at, new_inspected_at, fingerprint, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, segmentId);
            ps.setString(3, oldRunId);
            ps.setString(4, newRunId);
            ps.setDate(5, Date.valueOf(oldInspectedAt));
            ps.setDate(6, Date.valueOf(newInspectedAt));
            ps.setString(7, fingerprint);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 按幂等键查询批次。
     */
    public Optional<BatchRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM alignment_batch WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按主键查询批次。
     */
    public Optional<BatchRow> findById(long id) {
        return jdbc.query("SELECT * FROM alignment_batch WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键查询并加行级写锁（须在事务内调用），用于复核与封板的串行化。
     */
    public Optional<BatchRow> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM alignment_batch WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 将 OPEN 批次迁移为 SEALED（乐观锁校验版本号）。
     *
     * @return 实际迁移成功返回 true；批次不存在、版本不符或已封板返回 false
     */
    public boolean seal(long id, long expectedVersion, LocalDateTime sealedAt) {
        int updated = jdbc.update(
                "UPDATE alignment_batch SET status = 'SEALED', sealed_at = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND status = 'OPEN'",
                Timestamp.valueOf(sealedAt), id, expectedVersion);
        return updated == 1;
    }
}
