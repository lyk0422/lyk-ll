package com.example.starter.reading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 迟到事件仓储：日结封存后到达的检测只记录迟到事件。
 */
@Repository
public class LateEventRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LateEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(Long stationId, String sourceType, String requestId,
                       LocalDateTime detectedAt, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize late event", e);
        }
        jdbc.update("""
                        INSERT INTO late_event (station_id, source_type, request_id, detected_at, payload_json)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                stationId, sourceType, requestId, detectedAt, json);
    }

    public long countByRequest(String requestId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE request_id = ?", Long.class, requestId);
        return count == null ? 0 : count;
    }
}
