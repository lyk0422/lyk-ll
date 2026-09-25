package com.example.starter.recheck;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 留样记录数据访问，每批次至多一条（批次唯一约束）。
 */
@Repository
public class RetentionRepository {

    private final JdbcClient jdbc;

    public RetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记留样，批次唯一约束防止重复登记；返回生成主键。
     */
    public long insert(long batchId, LocalDateTime retainUntil, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO retention (batch_id, retain_until, created_at) VALUES (?, ?, ?)")
                .params(batchId, retainUntil, now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Retention> findByBatchId(long batchId) {
        return jdbc.sql("SELECT * FROM retention WHERE batch_id = ?")
                .param(batchId)
                .query(this::map)
                .optional();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM retention").query(Long.class).single();
    }

    private Retention map(ResultSet rs, int rowNum) throws SQLException {
        return new Retention(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getTimestamp("retain_until").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
