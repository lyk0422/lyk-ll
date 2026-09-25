package com.example.starter.deicing.repository;

import com.example.starter.deicing.po.IdempotentOperationPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 幂等操作台账仓储；主键 (request_id, operation) 保证并发下唯一受理。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotentOperationPO> MAPPER = (rs, rowNum) -> {
        IdempotentOperationPO po = new IdempotentOperationPO();
        po.setRequestId(rs.getString("request_id"));
        po.setOperation(rs.getString("operation"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setResponseStatus(rs.getInt("response_status"));
        po.setResponseBody(rs.getString("response_body"));
        po.setCreatedTs(rs.getLong("created_ts"));
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param requestId 申请号
     * @param operation 操作类型
     * @return 首次操作台账；不存在返回 null
     */
    public IdempotentOperationPO find(String requestId, String operation) {
        List<IdempotentOperationPO> rows = jdbcTemplate.query(
                "SELECT * FROM idempotent_operation WHERE request_id = ? AND operation = ?",
                MAPPER, requestId, operation);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 新增台账行。
     *
     * @param po 台账行
     * @throws DuplicateKeyException 并发下已有线程受理同一操作
     */
    public void insert(IdempotentOperationPO po) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_operation (request_id, operation, fingerprint, "
                        + "response_status, response_body, created_ts) VALUES (?, ?, ?, ?, ?, ?)",
                po.getRequestId(), po.getOperation(), po.getFingerprint(),
                po.getResponseStatus(), po.getResponseBody(), po.getCreatedTs());
    }

    /**
     * 抢占式受理成功后回填首次响应。
     *
     * @param requestId      申请号
     * @param operation      操作类型
     * @param responseStatus 首次响应 HTTP 状态码
     * @param responseBody   首次响应体 JSON
     */
    public void updateOutcome(String requestId, String operation, int responseStatus,
                              String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotent_operation SET response_status = ?, response_body = ? "
                        + "WHERE request_id = ? AND operation = ?",
                responseStatus, responseBody, requestId, operation);
    }
}
