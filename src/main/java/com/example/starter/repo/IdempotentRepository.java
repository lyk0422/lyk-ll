package com.example.starter.repo;

import java.sql.Timestamp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.IdempotentRecord;

/**
 * 幂等记录数据访问。request_id 唯一约束兜底并发：并发请求仅一个 tryInsert 成功。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotentRecord> MAPPER = (rs, n) -> {
        IdempotentRecord r = new IdempotentRecord();
        r.setId(rs.getLong("id"));
        r.setRequestId(rs.getString("request_id"));
        r.setBizType(rs.getString("biz_type"));
        r.setFingerprint(rs.getString("fingerprint"));
        long refId = rs.getLong("ref_id");
        r.setRefId(rs.wasNull() ? null : refId);
        r.setResponseBody(rs.getString("response_body"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };

    public IdempotentRecord findByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM idempotent_record WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 插入成功返回 true；唯一键冲突（已被并发请求受理）返回 false。 */
    public boolean tryInsert(IdempotentRecord record) {
        try {
            jdbc.update("""
                    INSERT INTO idempotent_record
                        (request_id, biz_type, fingerprint, ref_id, response_body, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    record.getRequestId(),
                    record.getBizType(),
                    record.getFingerprint(),
                    record.getRefId(),
                    record.getResponseBody() == null ? "" : record.getResponseBody(),
                    Timestamp.from(record.getCreatedAt()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void updateRefAndResponse(String requestId, Long refId, String responseBody) {
        jdbc.update("UPDATE idempotent_record SET ref_id = ?, response_body = ? WHERE request_id = ?",
                refId, responseBody, requestId);
    }
}
