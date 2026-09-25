package com.example.starter.repo;

import com.example.starter.domain.RequestType;
import com.example.starter.po.IdempotentRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 幂等请求记录数据访问。
 *
 * <p>两阶段语义：{@link #claim} 以主键唯一约束抢占受理（PENDING），
 * 业务完成后 {@link #complete} 写入首次响应；业务失败时 {@link #release} 释放，
 * 允许同参重试。同键并发时后到者等待先到者完成。</p>
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, n) -> {
        IdempotentRequest r = new IdempotentRequest();
        r.setRequestId(rs.getString("request_id"));
        r.setRequestType(RequestType.valueOf(rs.getString("request_type")));
        r.setStatus(rs.getString("status"));
        r.setFingerprint(rs.getString("fingerprint"));
        r.setStationId(rs.getLong("station_id"));
        int status = rs.getInt("http_status");
        r.setHttpStatus(rs.wasNull() ? null : status);
        r.setResponseBody(rs.getString("response_body"));
        r.setReplayCount(rs.getInt("replay_count"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp completed = rs.getTimestamp("completed_at");
        r.setCompletedAt(completed == null ? null : completed.toInstant());
        return r;
    };

    private static final String COLUMNS =
            "SELECT request_id, request_type, status, fingerprint, station_id, http_status, response_body, "
                    + "replay_count, created_at, completed_at FROM idempotent_request ";

    public IdempotentRequest find(String requestId) {
        List<IdempotentRequest> list = jdbc.query(COLUMNS + "WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 抢占受理：插入 PENDING 记录；主键冲突返回 false。
     */
    public boolean claim(String requestId, RequestType type, String fingerprint, long stationId, Instant now) {
        try {
            jdbc.update("INSERT INTO idempotent_request "
                            + "(request_id, request_type, status, fingerprint, station_id, replay_count, created_at) "
                            + "VALUES (?, ?, 'PENDING', ?, ?, 0, ?)",
                    requestId, type.name(), fingerprint, stationId, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 写入首次响应并置为完成。
     */
    public void complete(String requestId, int httpStatus, String responseBody, Instant now) {
        jdbc.update("UPDATE idempotent_request SET status = 'COMPLETED', http_status = ?, response_body = ?, "
                        + "completed_at = ? WHERE request_id = ?",
                httpStatus, responseBody, Timestamp.from(now), requestId);
    }

    /**
     * 业务失败时释放受理记录，允许同参重试。
     */
    public void release(String requestId) {
        jdbc.update("DELETE FROM idempotent_request WHERE request_id = ? AND status = 'PENDING'", requestId);
    }

    public void incrementReplay(String requestId) {
        jdbc.update("UPDATE idempotent_request SET replay_count = replay_count + 1 WHERE request_id = ?", requestId);
    }
}
