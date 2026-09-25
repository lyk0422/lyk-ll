package com.example.starter.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 复电双确认链：调度与现场各确认一次，双方齐备方可复电。
 */
@Repository
public class RestoreConfirmDao {

    private final JdbcTemplate jdbc;

    public RestoreConfirmDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initRowIfAbsent(long orderId) {
        jdbc.update("INSERT INTO restore_confirm (order_id) SELECT ? WHERE NOT EXISTS"
                + " (SELECT 1 FROM restore_confirm WHERE order_id = ?)", orderId, orderId);
    }

    /**
     * 记录某一方确认；返回更新后链状态。
     */
    public ConfirmRow confirm(long orderId, String side, Instant time, String actor) {
        String timeColumn = "DISPATCHER".equals(side) ? "dispatcher_confirm_time" : "field_confirm_time";
        String actorColumn = "DISPATCHER".equals(side) ? "dispatcher_actor" : "field_actor";
        jdbc.update(
                "UPDATE restore_confirm SET " + timeColumn + " = ?, " + actorColumn + " = ? WHERE order_id = ?",
                Timestamp.from(time), actor, orderId);
        return get(orderId).orElseThrow();
    }

    public Optional<ConfirmRow> get(long orderId) {
        List<ConfirmRow> list = jdbc.query(
                "SELECT order_id, dispatcher_confirm_time, dispatcher_actor,"
                        + " field_confirm_time, field_actor, completed_time"
                        + " FROM restore_confirm WHERE order_id = ?",
                (rs, n) -> mapRow(rs), orderId);
        return list.stream().findFirst();
    }

    public void markCompleted(long orderId, Instant completedTime) {
        jdbc.update("UPDATE restore_confirm SET completed_time = ? WHERE order_id = ?",
                Timestamp.from(completedTime), orderId);
    }

    private ConfirmRow mapRow(ResultSet rs) throws SQLException {
        return new ConfirmRow(
                rs.getLong("order_id"),
                toInstant(rs.getTimestamp("dispatcher_confirm_time")),
                rs.getString("dispatcher_actor"),
                toInstant(rs.getTimestamp("field_confirm_time")),
                rs.getString("field_actor"),
                toInstant(rs.getTimestamp("completed_time")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 确认链行。
     */
    public record ConfirmRow(long orderId, Instant dispatcherTime, String dispatcherActor,
                             Instant fieldTime, String fieldActor, Instant completedTime) {

        public boolean dispatcherConfirmed() {
            return dispatcherTime != null;
        }

        public boolean fieldConfirmed() {
            return fieldTime != null;
        }

        public boolean bothConfirmed() {
            return dispatcherConfirmed() && fieldConfirmed();
        }
    }
}
