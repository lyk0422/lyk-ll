package com.example.starter.ili;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * ili_batch 表的数据访问。
 */
@Repository
public class IliBatchRepository {

    private static final RowMapper<BatchRow> MAPPER = IliBatchRepository::mapRow;

    private final JdbcTemplate jdbc;

    public IliBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新批次（状态 ALIGNED）并返回生成的主键；requestId 冲突时抛出 DuplicateKeyException。
     */
    public long insert(String requestId, String fingerprint, String segmentId,
            String runOldId, String runNewId,
            java.time.LocalDate runOldDate, java.time.LocalDate runNewDate, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO ili_batch (request_id, fingerprint, segment_id, run_old_id, run_new_id,"
                            + " run_old_date, run_new_date, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ALIGNED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, segmentId);
            ps.setString(4, runOldId);
            ps.setString(5, runNewId);
            ps.setObject(6, runOldDate);
            ps.setObject(7, runNewDate);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("批次主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 按幂等标识查询批次。
     */
    public Optional<BatchRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM ili_batch WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按主键查询批次。
     */
    public Optional<BatchRow> findById(long id) {
        return jdbc.query("SELECT * FROM ili_batch WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键加行锁查询批次（SELECT ... FOR UPDATE），用于封板与复核的串行化。
     */
    public Optional<BatchRow> lockById(long id) {
        return jdbc.query("SELECT * FROM ili_batch WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 将批次置为已封板。
     */
    public void markSealed(long id, Instant sealedAt) {
        jdbc.update("UPDATE ili_batch SET status = 'SEALED', sealed_at = ? WHERE id = ?",
                Timestamp.from(sealedAt), id);
    }

    private static BatchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp sealedAt = rs.getTimestamp("sealed_at");
        return new BatchRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("segment_id"),
                rs.getString("run_old_id"),
                rs.getString("run_new_id"),
                rs.getObject("run_old_date", java.time.LocalDate.class),
                rs.getObject("run_new_date", java.time.LocalDate.class),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                sealedAt == null ? null : sealedAt.toInstant());
    }
}
