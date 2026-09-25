package com.example.starter.repository;

import com.example.starter.domain.IdempotentRequest;
import com.example.starter.domain.RequestType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 幂等请求记录数据访问。requestId 为主键，插入冲突即说明该键已有首次结果。
 */
@Repository
public class IdempotentRepository {

    private static final RowMapper<IdempotentRequest> ROW_MAPPER = (rs, rowNum) -> {
        long lotIdValue = rs.getLong("lot_id");
        Long lotId = rs.wasNull() ? null : lotIdValue;
        return new IdempotentRequest(
                rs.getString("request_id"),
                RequestType.valueOf(rs.getString("request_type")),
                rs.getString("fingerprint"),
                lotId,
                rs.getString("result_ref"),
                rs.getString("result_snapshot"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 尝试占用 requestId。
     *
     * @return true 首次占用成功；false 该 requestId 已存在（调用方须重读判定同参/异参）
     */
    public boolean tryInsert(IdempotentRequest request) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO idempotent_request
                        (request_id, request_type, fingerprint, lot_id, result_ref,
                         result_snapshot, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    request.requestId(), request.requestType().name(), request.fingerprint(),
                    request.lotId(), request.resultRef(), request.resultSnapshot(),
                    Timestamp.from(request.createdAt()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public IdempotentRequest findByRequestId(String requestId) {
        List<IdempotentRequest> list = jdbcTemplate.query(
                "SELECT * FROM idempotent_request WHERE request_id = ?", ROW_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 首次业务处理成功后回填关联批次、结果引用与响应快照。 */
    public void bindResult(String requestId, long lotId, String resultRef, String resultSnapshot) {
        jdbcTemplate.update("""
                UPDATE idempotent_request
                   SET lot_id = ?,
                       result_ref = ?,
                       result_snapshot = ?
                 WHERE request_id = ?
                """,
                lotId, resultRef, resultSnapshot, requestId);
    }
}
