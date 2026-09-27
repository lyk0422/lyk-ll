package com.example.starter.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.example.starter.support.AppClock;
import com.example.starter.web.NotFoundException;

/**
 * 工单存储助手：工单行查询、状态流转与轨迹事件的公共实现。
 */
@Component
public class WorkOrderStore {

    /** 优先级排序片段：重要等级降序、影响户数降序、接报先后升序。 */
    static final String PRIORITY_ORDER =
            " ORDER BY CASE important_level WHEN 'SPECIAL' THEN 3 WHEN 'FIRST' THEN 2"
            + " WHEN 'SECOND' THEN 1 ELSE 0 END DESC, affected_users DESC, outage_start_at ASC, id ASC";

    private static final RowMapper<WorkOrderRow> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public WorkOrderStore(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static WorkOrderRow mapRow(ResultSet rs) throws SQLException {
        return mapRowPublic(rs);
    }

    /**
     * 公共工单行映射：供其他服务在加锁查询（SELECT ... FOR UPDATE）时复用同一映射。
     */
    public static WorkOrderRow mapRowPublic(ResultSet rs) throws SQLException {
        long teamId = rs.getLong("team_id");
        Long team = rs.wasNull() ? null : teamId;
        Timestamp restored = rs.getTimestamp("restored_at");
        long mergedInto = rs.getLong("merged_into_id");
        Long merged = rs.wasNull() ? null : mergedInto;
        return new WorkOrderRow(
                rs.getLong("id"),
                rs.getString("feeder_id"),
                rs.getString("fault_point"),
                rs.getInt("affected_users"),
                rs.getString("important_level"),
                rs.getString("status"),
                team,
                rs.getTimestamp("outage_start_at").toLocalDateTime(),
                restored == null ? null : restored.toLocalDateTime(),
                merged);
    }

    /**
     * 按ID查询工单，不存在返回空。
     */
    public Optional<WorkOrderRow> findById(long id) {
        List<WorkOrderRow> rows = jdbc.query(
                "SELECT * FROM work_order WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按ID查询工单，不存在抛 404。
     */
    public WorkOrderRow requireById(long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("工单不存在: " + id));
    }

    /**
     * 新建工单，返回生成的工单ID。
     */
    public long insertOrder(String feederId, String faultPoint, int affectedUsers,
            String importantLevel, String status, LocalDateTime outageStartAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO work_order (feeder_id, fault_point, affected_users, important_level,"
                    + " status, outage_start_at, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, feederId);
            ps.setString(2, faultPoint);
            ps.setInt(3, affectedUsers);
            ps.setString(4, importantLevel);
            ps.setString(5, status);
            ps.setTimestamp(6, Timestamp.valueOf(outageStartAt));
            ps.setTimestamp(7, Timestamp.valueOf(clock.now()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 记录工单轨迹事件。
     */
    public void addEvent(long workOrderId, String eventType, String detail) {
        jdbc.update(
                "INSERT INTO work_order_event (work_order_id, event_type, detail, created_at) VALUES (?,?,?,?)",
                workOrderId, eventType, detail, Timestamp.valueOf(clock.now()));
    }

    /**
     * 查询工单轨迹事件（按时间、ID升序），格式为“类型:详情”。
     */
    public List<String> listEvents(long workOrderId) {
        return jdbc.query(
                "SELECT event_type, detail FROM work_order_event WHERE work_order_id = ?"
                + " ORDER BY created_at ASC, id ASC",
                (rs, rowNum) -> {
                    String detail = rs.getString("detail");
                    return rs.getString("event_type") + (detail == null ? "" : ":" + detail);
                },
                workOrderId);
    }
}
