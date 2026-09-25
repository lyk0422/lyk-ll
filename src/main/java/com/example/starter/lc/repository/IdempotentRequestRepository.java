package com.example.starter.lc.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.lc.domain.IdempotentRequest;
import com.example.starter.lc.domain.OperationType;

/**
 * 幂等请求记录数据访问。request_id 主键保证同键唯一受理。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 requestId 查询已有记录，不存在返回 null。 */
    public IdempotentRequest findByRequestId(String requestId) {
        List<IdempotentRequest> list = jdbcTemplate.query(
                "SELECT request_id, operation_type, fingerprint, http_status, response_body, created_at_utc"
                        + " FROM idempotent_request WHERE request_id = ?",
                ROW_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入首次请求记录。并发同键插入时由主键冲突抛出
     * {@link DuplicateKeyException}，调用方应重新查询并按重放/冲突处理。
     */
    public void insert(IdempotentRequest request) {
        jdbcTemplate.update("""
                INSERT INTO idempotent_request
                  (request_id, operation_type, fingerprint, http_status, response_body, created_at_utc)
                VALUES (?,?,?,?,?,?)
                """,
                request.getRequestId(), request.getOperationType().name(), request.getFingerprint(),
                request.getHttpStatus(), request.getResponseBody(),
                Timestamp.valueOf(request.getCreatedAtUtc()));
    }

    private static final RowMapper<IdempotentRequest> ROW_MAPPER = (rs, rowNum) -> {
        IdempotentRequest r = new IdempotentRequest();
        r.setRequestId(rs.getString("request_id"));
        r.setOperationType(OperationType.valueOf(rs.getString("operation_type")));
        r.setFingerprint(rs.getString("fingerprint"));
        r.setHttpStatus(rs.getInt("http_status"));
        r.setResponseBody(rs.getString("response_body"));
        r.setCreatedAtUtc(rs.getTimestamp("created_at_utc").toLocalDateTime());
        return r;
    };
}
