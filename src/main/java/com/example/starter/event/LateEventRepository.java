package com.example.starter.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 迟到事件数据访问。
 */
@Repository
public class LateEventRepository {

    private final JdbcClient jdbc;

    public LateEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录迟到事件；返回生成主键。
     */
    public long insert(long batchId, String eventType, String detail, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO late_event (batch_id, event_type, detail, created_at) VALUES (?, ?, ?, ?)")
                .params(batchId, eventType, detail, now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<LateEvent> findByBatchId(long batchId) {
        return jdbc.sql("SELECT * FROM late_event WHERE batch_id = ? ORDER BY id")
                .param(batchId)
                .query(this::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM late_event").query(Long.class).single();
    }

    private LateEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new LateEvent(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("event_type"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
