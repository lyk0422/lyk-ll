package com.example.starter.dye.repo;

import com.example.starter.dye.domain.IdempotentRequestPO;
import com.example.starter.dye.domain.OperationType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等请求登记仓储：requestId 为主键，指纹与首次结果 JSON 持久化。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotentRequestPO> MAPPER = (rs, n) -> {
        IdempotentRequestPO po = new IdempotentRequestPO();
        po.setRequestId(rs.getString("request_id"));
        po.setOperation(OperationType.valueOf(rs.getString("operation")));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setStatusCode(rs.getInt("status_code"));
        po.setResponseClob(rs.getString("response_clob"));
        Timestamp ts = rs.getTimestamp("created_at");
        po.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return po;
    };

    public Optional<IdempotentRequestPO> find(String requestId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select request_id, operation, fingerprint, status_code, response_clob, created_at "
                            + "from idempotent_request where request_id = ?", MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 预占幂等键（事务内）：先插入占位行，业务成功后再回填结果；业务回滚则占位行一并回滚。
     */
    public void insertPlaceholder(String requestId, OperationType operation, String fingerprint,
                                  LocalDateTime now) {
        jdbc.update("insert into idempotent_request (request_id, operation, fingerprint, status_code, "
                        + "response_clob, created_at) values (?, ?, ?, ?, ?, ?)",
                requestId, operation.name(), fingerprint, 0, "", Timestamp.valueOf(now));
    }

    /**
     * 业务成功后回填首次结果与状态码（同一事务内）。
     */
    public void fillResult(String requestId, int statusCode, String responseJson) {
        jdbc.update("update idempotent_request set status_code = ?, response_clob = ? where request_id = ?",
                statusCode, responseJson, requestId);
    }
}
