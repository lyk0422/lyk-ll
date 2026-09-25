package com.example.starter.idempotent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等记录仓储：主键即 requestId，插入冲突用于并发重发的唯一裁决。
 */
@Repository
public class IdempotentRecordRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotentRecord> find(String requestId) {
        return jdbc.query(
                "SELECT request_id, station_id, api_type, fingerprint, http_status, result_json, created_at "
                        + "FROM idempotent_record WHERE request_id = ?",
                rs -> rs.next() ? Optional.of(new IdempotentRecord(
                        rs.getString("request_id"),
                        (Long) rs.getObject("station_id"),
                        rs.getString("api_type"),
                        rs.getString("fingerprint"),
                        rs.getInt("http_status"),
                        rs.getString("result_json"),
                        rs.getTimestamp("created_at").toLocalDateTime())) : Optional.empty(),
                requestId);
    }

    public void insert(IdempotentRecord record) {
        jdbc.update("INSERT INTO idempotent_record (request_id, station_id, api_type, fingerprint, "
                        + "http_status, result_json, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.requestId(), record.stationId(), record.apiType(), record.fingerprint(),
                record.httpStatus(), record.resultJson());
    }

    public void updateResult(String requestId, int httpStatus, String resultJson) {
        jdbc.update("UPDATE idempotent_record SET http_status = ?, result_json = ? WHERE request_id = ?",
                httpStatus, resultJson, requestId);
    }
}
