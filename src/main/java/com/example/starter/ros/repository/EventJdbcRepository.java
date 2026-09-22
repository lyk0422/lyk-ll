package com.example.starter.ros.repository;

import com.example.starter.ros.domain.EventType;
import com.example.starter.ros.domain.NodeEvent;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 资源变更事件表 node_event 与订阅水位表 subscriber_cursor 数据访问。
 * revision 由数据库 AUTO_INCREMENT 全局分配,严格递增。
 */
@Repository
public class EventJdbcRepository {

    private static final EventRowMapper ROW_MAPPER = new EventRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public EventJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 发布事件并返回带全局递增 revision 的事件。
     */
    public NodeEvent insert(String nodeId, EventType eventType, String payloadJson,
                            LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO node_event (node_id, event_type, payload, confirmed, created_at)
                            VALUES (?, ?, ?, 0, ?)
                            """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, nodeId);
            ps.setString(2, eventType.name());
            ps.setString(3, payloadJson);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        long revision = keyHolder.getKey().longValue();
        return new NodeEvent(revision, nodeId, eventType, payloadJson, false, now);
    }

    /**
     * 拉取 revision 大于 afterRevision 的事件,按 revision 升序,最多 limit 条。
     */
    public List<NodeEvent> findAfter(long afterRevision, int limit) {
        return jdbcTemplate.query("""
                        SELECT revision, node_id, event_type, payload, confirmed, created_at
                          FROM node_event
                         WHERE revision > ?
                         ORDER BY revision ASC
                         LIMIT ?
                        """, ROW_MAPPER, afterRevision, limit);
    }

    public long getCursor(String subscriberId) {
        List<Long> cursors = jdbcTemplate.query(
                "SELECT last_revision FROM subscriber_cursor WHERE subscriber_id = ?",
                (rs, rowNum) -> rs.getLong(1), subscriberId);
        return cursors.stream().findFirst().orElse(0L);
    }

    /**
     * 水位只允许单调前进:不存在则以 0 初始化,再仅在传入 revision 更大时更新。
     * 返回更新后的水位(可能因乱序确认而保持原值)。
     */
    public long advanceCursor(String subscriberId, long revision) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO subscriber_cursor (subscriber_id, last_revision) VALUES (?, 0)",
                    subscriberId);
        } catch (DuplicateKeyException ignored) {
            // 订阅方水位已存在,无需初始化
        }
        jdbcTemplate.update("""
                        UPDATE subscriber_cursor
                           SET last_revision = ?
                         WHERE subscriber_id = ? AND last_revision < ?
                        """, revision, subscriberId, revision);
        long current = getCursor(subscriberId);
        // 任一订阅方确认到该水位,即把对应事件标记为已确认(confirmed 为全局标记,
        // 各订阅方各自的消费进度仍由 subscriber_cursor 独立维护)。
        jdbcTemplate.update("UPDATE node_event SET confirmed = 1 WHERE revision <= ?", current);
        return current;
    }

    private static final class EventRowMapper implements RowMapper<NodeEvent> {
        @Override
        public NodeEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new NodeEvent(
                    rs.getLong("revision"),
                    rs.getString("node_id"),
                    EventType.valueOf(rs.getString("event_type")),
                    rs.getString("payload"),
                    rs.getInt("confirmed") == 1,
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    }
}
