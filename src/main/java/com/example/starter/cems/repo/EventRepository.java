package com.example.starter.cems.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等事件与排口事件流仓储。
 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等记录行。 */
    public record IdempotentRow(String requestId, String action, String outfallCode,
                                String fingerprint, String requestJson, String resultJson,
                                LocalDateTime createdAt) {
    }

    /** 排口事件行。 */
    public record OutfallEventRow(long id, String outfallCode, String eventType,
                                  String detail, LocalDateTime createdAt) {
    }

    /** 读取幂等记录；不存在返回 null。 */
    public IdempotentRow findIdempotent(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT request_id, action, outfall_code, fingerprint, request_json, "
                            + "result_json, created_at FROM idempotent_event WHERE request_id = ?",
                    (rs, n) -> new IdempotentRow(
                            rs.getString("request_id"),
                            rs.getString("action"),
                            rs.getString("outfall_code"),
                            rs.getString("fingerprint"),
                            rs.getString("request_json"),
                            rs.getString("result_json"),
                            rs.getTimestamp("created_at").toLocalDateTime()),
                    requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 写入幂等记录；重复 requestId 由主键拒绝。 */
    public void insertIdempotent(String requestId, String action, String outfallCode,
                                 String fingerprint, String requestJson, String resultJson,
                                 LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO idempotent_event(request_id, action, outfall_code, fingerprint, "
                        + "request_json, result_json, created_at) VALUES (?,?,?,?,?,?,?)",
                requestId, action, outfallCode, fingerprint, requestJson, resultJson,
                Timestamp.valueOf(createdAt));
    }

    /** 首次业务处理完成后写回响应 JSON。 */
    public void updateIdempotentResult(String requestId, String resultJson) {
        int rows = jdbc.update(
                "UPDATE idempotent_event SET result_json = ? WHERE request_id = ?",
                resultJson, requestId);
        if (rows != 1) {
            throw new IllegalStateException("幂等结果写回失败: " + requestId);
        }
    }

    /** 记录排口事件（迟到数据、缺失督办、限排、解除、重复归档等）。 */
    public void insertOutfallEvent(String outfallCode, String eventType,
                                   String detail, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO outfall_event(outfall_code, event_type, detail, created_at) "
                        + "VALUES (?,?,?,?)",
                outfallCode, eventType, detail, Timestamp.valueOf(createdAt));
    }

    /** 按时间正序列出排口事件。 */
    public List<OutfallEventRow> findEvents(String outfallCode) {
        return jdbc.query(
                "SELECT * FROM outfall_event WHERE outfall_code = ? ORDER BY id",
                (rs, n) -> new OutfallEventRow(
                        rs.getLong("id"),
                        rs.getString("outfall_code"),
                        rs.getString("event_type"),
                        rs.getString("detail"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                outfallCode);
    }

    /** 测试清理。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM idempotent_event");
        jdbc.update("DELETE FROM outfall_event");
    }
}
