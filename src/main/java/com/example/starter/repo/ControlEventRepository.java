package com.example.starter.repo;

import com.example.starter.domain.ControlEventStatus;
import com.example.starter.domain.ControlEventType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 管制事件数据访问。
 */
@Repository
public class ControlEventRepository {

    /** 管制事件行记录。 */
    public record ControlEventRow(long id, long lineId, ControlEventType eventType, ControlEventStatus status,
                                  long triggerSegmentId, BigDecimal triggerAvgSpeed,
                                  LocalDateTime windowStart, LocalDateTime windowEnd,
                                  LocalDateTime createdAt, LocalDateTime closedAt) {
    }

    private final JdbcTemplate jdbc;

    public ControlEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long lineId, ControlEventType type, ControlEventStatus status, long triggerSegmentId,
                       BigDecimal triggerAvgSpeed, LocalDateTime windowStart, LocalDateTime windowEnd,
                       LocalDateTime createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO control_event (line_id, event_type, status, "
                            + "trigger_segment_id, trigger_avg_speed, window_start, window_end, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, lineId);
            ps.setString(2, type.name());
            ps.setString(3, status.name());
            ps.setLong(4, triggerSegmentId);
            ps.setBigDecimal(5, triggerAvgSpeed);
            ps.setTimestamp(6, Timestamp.valueOf(windowStart));
            ps.setTimestamp(7, Timestamp.valueOf(windowEnd));
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("管制事件写入未返回主键");
        }
        return key.longValue();
    }

    public Optional<ControlEventRow> findById(long id) {
        return jdbc.query("SELECT id, line_id, event_type, status, trigger_segment_id, trigger_avg_speed, "
                + "window_start, window_end, created_at, closed_at FROM control_event WHERE id = ?",
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    /** 查询线路进行中（未归档）的管制事件。 */
    public Optional<ControlEventRow> findOpenByLineId(long lineId) {
        return jdbc.query("SELECT id, line_id, event_type, status, trigger_segment_id, trigger_avg_speed, "
                        + "window_start, window_end, created_at, closed_at FROM control_event "
                        + "WHERE line_id = ? AND status <> 'CLOSED' ORDER BY id DESC",
                (rs, n) -> map(rs), lineId).stream().findFirst();
    }

    /** 线路全部事件的最大判定窗口结束分钟；用于识别迟到数据。 */
    public Optional<LocalDateTime> maxWindowEndByLineId(long lineId) {
        return jdbc.query("SELECT MAX(window_end) AS we FROM control_event WHERE line_id = ?",
                (rs, n) -> {
                    Timestamp ts = rs.getTimestamp("we");
                    return ts == null ? null : ts.toLocalDateTime();
                }, lineId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public void updateStatus(long id, ControlEventStatus status, LocalDateTime closedAt) {
        if (closedAt == null) {
            jdbc.update("UPDATE control_event SET status = ? WHERE id = ?", status.name(), id);
        } else {
            jdbc.update("UPDATE control_event SET status = ?, closed_at = ? WHERE id = ?",
                    status.name(), Timestamp.valueOf(closedAt), id);
        }
    }

    public long countOpenByLineId(long lineId) {
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM control_event WHERE line_id = ? AND status <> 'CLOSED'",
                Long.class, lineId);
        return cnt == null ? 0 : cnt;
    }

    private ControlEventRow map(ResultSet rs) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new ControlEventRow(rs.getLong("id"), rs.getLong("line_id"),
                ControlEventType.valueOf(rs.getString("event_type")),
                ControlEventStatus.valueOf(rs.getString("status")),
                rs.getLong("trigger_segment_id"), rs.getBigDecimal("trigger_avg_speed"),
                rs.getTimestamp("window_start").toLocalDateTime(),
                rs.getTimestamp("window_end").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                closed == null ? null : closed.toLocalDateTime());
    }
}
