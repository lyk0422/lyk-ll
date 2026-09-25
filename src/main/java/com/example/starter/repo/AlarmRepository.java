package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.domain.AlarmRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 告警/事件数据访问。告警链按 id 升序可复算。
 */
@Repository
public class AlarmRepository {

    private final JdbcTemplate jdbc;

    public AlarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long commandId, Long turbineId, String type, String message,
                       LocalDateTime createdAt) {
        jdbc.update("INSERT INTO alarm_event (command_id, turbine_id, type, message, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                commandId, turbineId, type, message, Timestamp.valueOf(createdAt));
    }

    public List<AlarmRow> findByCommandId(long commandId) {
        return jdbc.query(
                "SELECT id, command_id, turbine_id, type, message, created_at FROM alarm_event"
                        + " WHERE command_id = ? ORDER BY id",
                (rs, i) -> new AlarmRow(rs.getLong(1), rs.getLong(2),
                        rs.getObject(3) == null ? null : rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toLocalDateTime()),
                commandId);
    }
}
