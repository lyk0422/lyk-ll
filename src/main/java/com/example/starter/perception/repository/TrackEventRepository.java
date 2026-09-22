package com.example.starter.perception.repository;

import com.example.starter.perception.domain.TrackEvent;
import com.example.starter.perception.domain.TrackState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 轨迹事件流表（track_event）数据访问；事件流是快照重放的唯一事实来源。
 */
@Repository
public class TrackEventRepository {

    private static final TrackEventRowMapper ROW_MAPPER = new TrackEventRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public TrackEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加事件；event_id 唯一约束在数据库层保证幂等，重复 event_id 会触发约束异常。
     */
    public void append(TrackEvent event) {
        String sql = "INSERT INTO track_event (event_id, track_id, event_type, from_state, to_state, "
                + "version, detection_id, payload, event_time, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                event.getEventId(),
                event.getTrackId(),
                event.getEventType(),
                event.getFromState() == null ? null : event.getFromState().name(),
                event.getToState() == null ? null : event.getToState().name(),
                event.getVersion(),
                event.getDetectionId(),
                event.getPayload(),
                Timestamp.from(event.getEventTime()),
                Timestamp.from(event.getCreatedAt()));
    }

    /**
     * 查询指定轨迹 event_time &lt;= until 的事件，按 (event_time, id) 升序；
     * 同一时戳的事件按自增 id 确定全序，保证重放确定。
     */
    public List<TrackEvent> findByTrackUntil(String trackId, Instant until) {
        String sql = "SELECT id, event_id, track_id, event_type, from_state, to_state, version, "
                + "detection_id, payload, event_time, created_at FROM track_event "
                + "WHERE track_id = ? AND event_time <= ? ORDER BY event_time, id";
        return jdbcTemplate.query(sql, ROW_MAPPER, trackId, Timestamp.from(until));
    }

    /** 查询全部轨迹 event_time &lt;= until 的事件，按 (track_id, event_time, id) 升序。 */
    public List<TrackEvent> findAllUntil(Instant until) {
        String sql = "SELECT id, event_id, track_id, event_type, from_state, to_state, version, "
                + "detection_id, payload, event_time, created_at FROM track_event "
                + "WHERE event_time <= ? ORDER BY track_id, event_time, id";
        return jdbcTemplate.query(sql, ROW_MAPPER, Timestamp.from(until));
    }

    /** 事件流中出现过的全部轨迹ID。 */
    public List<String> findAllTrackIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT track_id FROM track_event ORDER BY track_id", String.class);
    }

    private static final class TrackEventRowMapper implements RowMapper<TrackEvent> {

        @Override
        public TrackEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            TrackEvent event = new TrackEvent();
            event.setId(rs.getLong("id"));
            event.setEventId(rs.getString("event_id"));
            event.setTrackId(rs.getString("track_id"));
            event.setEventType(rs.getString("event_type"));
            String from = rs.getString("from_state");
            if (from != null) {
                event.setFromState(TrackState.valueOf(from));
            }
            String to = rs.getString("to_state");
            if (to != null) {
                event.setToState(TrackState.valueOf(to));
            }
            event.setVersion(rs.getLong("version"));
            event.setDetectionId(rs.getString("detection_id"));
            event.setPayload(rs.getString("payload"));
            Timestamp eventTime = rs.getTimestamp("event_time");
            event.setEventTime(eventTime == null ? null : eventTime.toInstant());
            Timestamp createdAt = rs.getTimestamp("created_at");
            event.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
            return event;
        }
    }
}
