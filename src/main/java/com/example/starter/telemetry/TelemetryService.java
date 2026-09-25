package com.example.starter.telemetry;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.example.starter.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 遥信回报服务：按“间隔＋时标”去重；实际状态与该步预期不符即中止操作票并转异常；
 * 归档后迟到遥信只记迟到事件。
 */
@Service
public class TelemetryService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TelemetryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 遥信回报请求。 */
    public record TelemetryRequest(String intervalId, String deviceId, String state,
                                   Instant eventTime) {
    }

    /** 遥信回报响应。 */
    public record TelemetryView(Long eventId, boolean deduped, boolean late,
                                boolean orderAborted, String message) {
    }

    /**
     * 接收遥信：同间隔同时标去重；命中执行中票且与最近已执行步骤预期不符则中止该票。
     */
    @Transactional
    public TelemetryView report(TelemetryRequest req) {
        if (req.intervalId() == null || req.intervalId().isBlank()
                || req.deviceId() == null || req.deviceId().isBlank()
                || req.state() == null || req.state().isBlank()
                || req.eventTime() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TELEMETRY",
                    "间隔、设备、状态与时标均不能为空");
        }
        return doReport(req, false);
    }

    private TelemetryView doReport(TelemetryRequest req, boolean retried) {
        List<Long> existing = jdbc.query(
                "SELECT id FROM telemetry_event WHERE interval_id = ? AND event_time = ?",
                (rs, rowNum) -> rs.getLong("id"),
                req.intervalId(), Timestamp.from(req.eventTime()));
        if (!existing.isEmpty()) {
            return new TelemetryView(existing.get(0), true, false, false,
                    "重复遥信，按间隔+时标去重");
        }
        Instant now = clock.instant();
        List<Long> executing = jdbc.query(
                "SELECT id FROM switching_order WHERE status = 'EXECUTING' "
                        + "AND executing_interval = ?",
                (rs, rowNum) -> rs.getLong("id"), req.intervalId());
        boolean late = executing.isEmpty();
        boolean aborted = false;
        if (!late) {
            long orderId = executing.get(0);
            List<String> expected = jdbc.query(
                    "SELECT expected_state FROM order_step WHERE order_id = ? AND device_id = ? "
                            + "AND status = 'DONE' ORDER BY seq DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString("expected_state"),
                    orderId, req.deviceId());
            if (!expected.isEmpty() && !expected.get(0).equals(req.state())) {
                jdbc.update("UPDATE switching_order SET status = 'ABORTED', "
                                + "executing_interval = NULL, updated_at = ? WHERE id = ?",
                        Timestamp.from(now), orderId);
                jdbc.update("INSERT INTO order_event "
                                + "(order_id, event_type, actor, detail, event_at) VALUES (?,?,?,?,?)",
                        orderId, "ABORTED", null,
                        "遥信实际状态 " + req.state() + " 与该步预期 " + expected.get(0)
                                + " 不符，操作中止转异常", Timestamp.from(now));
                aborted = true;
            }
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO telemetry_event (interval_id, device_id, state, event_time, "
                                + "late, received_at) VALUES (?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, req.intervalId());
                ps.setString(2, req.deviceId());
                ps.setString(3, req.state());
                ps.setTimestamp(4, Timestamp.from(req.eventTime()));
                ps.setBoolean(5, late);
                ps.setTimestamp(6, Timestamp.from(now));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            if (retried) {
                throw e;
            }
            return doReport(req, true);
        }
        jdbc.update("UPDATE device_state SET state = ?, updated_at = ? WHERE device_id = ?",
                req.state(), Timestamp.from(now), req.deviceId());
        String message = late ? "无执行中操作票，记为迟到事件"
                : aborted ? "遥信与步骤预期不符，操作票已中止转异常" : "遥信已接收";
        return new TelemetryView(keyHolder.getKey().longValue(), false, late, aborted, message);
    }
}
