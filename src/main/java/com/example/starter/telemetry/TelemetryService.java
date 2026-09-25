package com.example.starter.telemetry;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.telemetry.TelemetryDtos.TelemetryRequest;
import com.example.starter.telemetry.TelemetryDtos.TelemetryResponse;
import com.example.starter.ticket.SwitchingOrderService;

/**
 * 遥信回报服务：按间隔＋时标去重；活动票逐步核对，
 * 实际状态与预期不符即中止操作并转异常；归档后迟到遥信仅记迟到事件。
 */
@Service
public class TelemetryService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SwitchingOrderService orderService;

    public TelemetryService(JdbcTemplate jdbc, Clock businessClock, SwitchingOrderService orderService) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.orderService = orderService;
    }

    /**
     * 登记并处理一条遥信回报。
     */
    @Transactional
    public TelemetryResponse record(TelemetryRequest req) {
        Long activeTicketId = orderService.activeTicketForInterval(req.intervalId());
        boolean late = activeTicketId == null && orderService.hasArchivedTicket(req.intervalId());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO telemetry (interval_id, device_id, ts, state, late, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, req.intervalId());
                ps.setString(2, req.deviceId());
                ps.setTimestamp(3, Timestamp.from(req.ts()));
                ps.setString(4, req.state());
                ps.setBoolean(5, late);
                ps.setTimestamp(6, Timestamp.from(Instant.now(clock)));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            // 间隔＋时标去重：重复回报返回首次记录
            Long existingId = jdbc.query(
                            "SELECT id FROM telemetry WHERE interval_id = ? AND ts = ?",
                            (rs, rowNum) -> rs.getLong(1), req.intervalId(), Timestamp.from(req.ts()))
                    .stream().findFirst().orElse(null);
            return new TelemetryResponse(existingId, "DEDUPLICATED", null);
        }
        long telemetryId = keyHolder.getKey().longValue();
        if (late) {
            return new TelemetryResponse(telemetryId, "LATE", null);
        }
        if (activeTicketId == null) {
            return new TelemetryResponse(telemetryId, "RECORDED", null);
        }
        String result = orderService.processTelemetry(activeTicketId, req.deviceId(), req.state());
        return new TelemetryResponse(telemetryId, result, activeTicketId);
    }
}
