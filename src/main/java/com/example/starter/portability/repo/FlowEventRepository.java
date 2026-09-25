package com.example.starter.portability.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.portability.FlowEvent;

/**
 * 流程事件表数据访问；事件只追加，按 id 升序查询即时间顺序。
 */
@Repository
public class FlowEventRepository {

    private final JdbcTemplate jdbc;

    public FlowEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long flowId, String eventType, String detail, Instant createdAt) {
        jdbc.update("INSERT INTO flow_event (flow_id, event_type, detail, created_at) VALUES (?,?,?,?)",
                flowId, eventType, detail, Timestamp.from(createdAt));
    }

    public List<FlowEvent> findByFlowIdOrderById(long flowId) {
        return jdbc.query("SELECT * FROM flow_event WHERE flow_id = ? ORDER BY id ASC",
                FlowEventRepository::mapRow, flowId);
    }

    private static FlowEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FlowEvent(
                rs.getLong("id"),
                rs.getLong("flow_id"),
                rs.getString("event_type"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant());
    }
}
