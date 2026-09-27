package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 跑道事件数据访问：runway_event 表。
 */
@Repository
public class RunwayEventRepository {

    private static final RowMapper<RunwayEvent> MAPPER = (rs, rowNum) -> new RunwayEvent(
            rs.getLong("id"),
            rs.getString("runway_id"),
            EventType.valueOf(rs.getString("event_type")),
            EventStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("reported_at").toInstant(),
            rs.getTimestamp("closed_at").toInstant(),
            rs.getTimestamp("inspection_deadline").toInstant(),
            rs.getBoolean("escalated"),
            rs.getString("assessment_type") == null ? null : AssessmentType.valueOf(rs.getString("assessment_type")),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public RunwayEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入事件；activeFlag 为 1 时占用“跑道唯一进行中事件”唯一索引。
     */
    public RunwayEvent insert(RunwayEvent event, Integer activeFlag) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO runway_event (runway_id, event_type, status, reported_at, closed_at,"
                            + " inspection_deadline, escalated, assessment_type, active_flag, request_id,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.runwayId());
            ps.setString(2, event.eventType().name());
            ps.setString(3, event.status().name());
            ps.setTimestamp(4, Timestamp.from(event.reportedAt()));
            ps.setTimestamp(5, Timestamp.from(event.closedAt()));
            ps.setTimestamp(6, Timestamp.from(event.inspectionDeadline()));
            ps.setBoolean(7, event.escalated());
            ps.setString(8, event.assessmentType() == null ? null : event.assessmentType().name());
            if (activeFlag == null) {
                ps.setNull(9, java.sql.Types.TINYINT);
            } else {
                ps.setInt(9, activeFlag);
            }
            ps.setString(10, event.requestId());
            ps.setTimestamp(11, Timestamp.from(event.createdAt()));
            ps.setTimestamp(12, Timestamp.from(event.updatedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<RunwayEvent> findById(long id) {
        List<RunwayEvent> rows = jdbc.query("SELECT * FROM runway_event WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 更新状态；clearActiveFlag 为真时释放“进行中”唯一占位（恢复/归档）。
     */
    public void updateStatus(long id, EventStatus status, boolean escalated, AssessmentType assessmentType,
                             boolean clearActiveFlag, java.time.Instant updatedAt) {
        jdbc.update("UPDATE runway_event SET status = ?, escalated = ?, assessment_type = ?,"
                        + " active_flag = CASE WHEN ? THEN NULL ELSE active_flag END, updated_at = ? WHERE id = ?",
                status.name(), escalated,
                assessmentType == null ? null : assessmentType.name(),
                clearActiveFlag, Timestamp.from(updatedAt), id);
    }

    public List<RunwayEvent> findAll() {
        return jdbc.query("SELECT * FROM runway_event ORDER BY id", MAPPER);
    }

    /** 行级锁定取某跑道进行中紧急关闭事件（active_flag=1），供例行开始校验与紧急抢占串行化。 */
    public List<RunwayEvent> findActiveByRunwayForUpdate(String runwayId) {
        return jdbc.query(
                "SELECT * FROM runway_event WHERE runway_id = ? AND active_flag = 1 ORDER BY id FOR UPDATE",
                MAPPER, runwayId);
    }

    /** 查询某跑道是否存在进行中紧急关闭事件（只读）。 */
    public boolean existsActiveByRunway(String runwayId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runway_event WHERE runway_id = ? AND active_flag = 1",
                Integer.class, runwayId);
        return count != null && count > 0;
    }
}
