package com.example.starter.transition.repo;

import com.example.starter.transition.model.IdempotentRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求仓储：指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotentRequestRepository {

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, n) -> {
        IdempotentRequest r = new IdempotentRequest();
        r.setRequestId(rs.getString("request_id"));
        r.setBizType(rs.getString("biz_type"));
        r.setFingerprint(rs.getString("fingerprint"));
        r.setResultRef(rs.getString("result_ref"));
        r.setResultJson(rs.getString("result_json"));
        r.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return r;
    };

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IdempotentRequest request, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO idempotent_request
                            (request_id, biz_type, fingerprint, result_ref, result_json, created_at)
                        VALUES (?,?,?,?,?,?)
                        """,
                request.getRequestId(), request.getBizType(), request.getFingerprint(),
                request.getResultRef(), request.getResultJson(), Timestamp.valueOf(now));
    }

    public Optional<IdempotentRequest> find(String requestId) {
        List<IdempotentRequest> list = jdbc.query(
                "SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId);
        return list.stream().findFirst();
    }

    /** 业务完成后回写首次结果，供同键重放原样返回。 */
    public void updateResult(String requestId, String resultRef, String resultJson) {
        jdbc.update("UPDATE idempotent_request SET result_ref = ?, result_json = ? WHERE request_id = ?",
                resultRef, resultJson, requestId);
    }
}
