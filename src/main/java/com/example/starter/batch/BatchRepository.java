package com.example.starter.batch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import com.example.starter.domain.BatchStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 进场批次数据访问，全部参数化 SQL。
 */
@Repository
public class BatchRepository {

    private final JdbcClient jdbc;

    public BatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入报备批次，request_id 唯一约束保证并发唯一受理；返回生成主键。
     */
    public long insert(String requestId, String fingerprint, String origin, String variety,
                       java.math.BigDecimal quantity, BatchStatus status, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO batch (request_id, fingerprint, origin, variety, quantity, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(requestId, fingerprint, origin, variety, quantity, status.name(), now, now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Batch> findById(long id) {
        return jdbc.sql("SELECT * FROM batch WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    public Optional<Batch> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM batch WHERE request_id = ?")
                .param(requestId)
                .query(this::map)
                .optional();
    }

    /**
     * 条件状态流转：仅当前状态等于 expected 时更新，返回受影响行数（0 表示状态已被并发修改）。
     */
    public int transitionStatus(long id, BatchStatus expected, BatchStatus target, LocalDateTime now) {
        return jdbc.sql("UPDATE batch SET status = ?, updated_at = ? WHERE id = ? AND status = ?")
                .params(target.name(), now, id, expected.name())
                .update();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM batch").query(Long.class).single();
    }

    private Batch map(ResultSet rs, int rowNum) throws SQLException {
        return new Batch(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("origin"),
                rs.getString("variety"),
                rs.getBigDecimal("quantity"),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
