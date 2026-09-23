package com.example.starter.breakmodel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 直播插播请求数据访问。(channel_id, request_id) 唯一，作为幂等键。
 */
@Repository
public class BreakRequestRepository {

    private static final String COLUMNS = "channel_id, request_id, priority, start_ms, end_ms, status, "
            + "fingerprint, start_response, end_response";

    private static final RowMapper<BreakRequestRow> MAPPER = (rs, rowNum) -> new BreakRequestRow(
            rs.getString("channel_id"),
            rs.getString("request_id"),
            rs.getInt("priority"),
            rs.getLong("start_ms"),
            rs.getObject("end_ms") != null ? rs.getLong("end_ms") : null,
            rs.getString("status"),
            rs.getString("fingerprint"),
            rs.getString("start_response"),
            rs.getString("end_response"));

    /**
     * 插播请求行：startResponse/endResponse 为首次请求的冻结响应 JSON。
     */
    public record BreakRequestRow(
            String channelId,
            String requestId,
            int priority,
            long startMs,
            Long endMs,
            String status,
            String fingerprint,
            String startResponse,
            String endResponse
    ) {
    }

    private final JdbcTemplate jdbc;

    public BreakRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(BreakRequestRow row) {
        jdbc.update("INSERT INTO break_request (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.channelId(), row.requestId(), row.priority(), row.startMs(), row.endMs(),
                row.status(), row.fingerprint(), row.startResponse(), row.endResponse());
    }

    public BreakRequestRow find(String channelId, String requestId) {
        List<BreakRequestRow> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM break_request WHERE channel_id = ? AND request_id = ?",
                MAPPER, channelId, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 当前进行中的插播；无则返回 null。
     */
    public BreakRequestRow findActive(String channelId) {
        List<BreakRequestRow> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM break_request WHERE channel_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY start_ms DESC LIMIT 1",
                MAPPER, channelId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void markEnded(String channelId, String requestId, long endMs, String endResponse) {
        jdbc.update("UPDATE break_request SET status = 'ENDED', end_ms = ?, end_response = ? "
                + "WHERE channel_id = ? AND request_id = ?", endMs, endResponse, channelId, requestId);
    }
}
