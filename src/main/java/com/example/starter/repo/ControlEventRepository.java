package com.example.starter.repo;

import com.example.starter.model.ControlEvent;
import com.example.starter.model.ControlEventStatus;
import com.example.starter.model.ControlEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 管制事件数据访问。
 */
@Repository
public class ControlEventRepository {

    private static final RowMapper<ControlEvent> MAPPER = (rs, n) -> new ControlEvent(
            rs.getLong("id"), rs.getLong("line_id"),
            ControlEventType.valueOf(rs.getString("type")),
            ControlEventStatus.valueOf(rs.getString("status")),
            rs.getObject("trigger_segment_id") == null ? null : rs.getLong("trigger_segment_id"),
            rs.getObject("avg_speed") == null ? null : rs.getDouble("avg_speed"),
            rs.getTimestamp("triggered_at").toInstant(),
            rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toInstant());

    private static final String COLUMNS =
            "id, line_id, type, status, trigger_segment_id, avg_speed, triggered_at, archived_at";

    private final JdbcTemplate jdbc;

    public ControlEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建管制事件，返回生成主键。
     */
    public long insert(long lineId, ControlEventType type, long triggerSegmentId, double avgSpeed,
                       Instant triggeredAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO control_event (line_id, type, status, trigger_segment_id, avg_speed, triggered_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, lineId);
            ps.setString(2, type.name());
            ps.setString(3, ControlEventStatus.ACTIVE.name());
            ps.setLong(4, triggerSegmentId);
            ps.setDouble(5, avgSpeed);
            ps.setTimestamp(6, Timestamp.from(triggeredAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 id 查询事件。
     */
    public Optional<ControlEvent> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM control_event WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 id 查询并加行锁（事务内串行化事件级写操作）。
     */
    public Optional<ControlEvent> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM control_event WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询线路进行中（未归档）的事件。
     */
    public Optional<ControlEvent> findInProgressByLine(long lineId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM control_event WHERE line_id = ? AND status <> ? "
                                + "ORDER BY id DESC",
                        MAPPER, lineId, ControlEventStatus.RESUMED.name())
                .stream().findFirst();
    }

    /**
     * 查询线路已归档（终态）事件。
     */
    public List<ControlEvent> findArchivedByLine(long lineId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM control_event WHERE line_id = ? AND status = ? ORDER BY id",
                MAPPER, lineId, ControlEventStatus.RESUMED.name());
    }

    /**
     * 线路最近一次归档事件的触发时刻，无归档事件返回 null（迟到数据判定用）。
     */
    public Instant latestArchivedTriggeredAt(long lineId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(triggered_at) FROM control_event WHERE line_id = ? AND status = ?",
                Timestamp.class, lineId, ControlEventStatus.RESUMED.name());
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 更新事件状态。
     */
    public void updateStatus(long id, ControlEventStatus status) {
        jdbc.update("UPDATE control_event SET status = ? WHERE id = ?", status.name(), id);
    }

    /**
     * 归档事件（置终态并记录归档时间）。
     */
    public void archive(long id, Instant archivedAt) {
        jdbc.update("UPDATE control_event SET status = ?, archived_at = ? WHERE id = ?",
                ControlEventStatus.RESUMED.name(), Timestamp.from(archivedAt), id);
    }
}
