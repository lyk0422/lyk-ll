package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.po.IdempotentRequest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等记录数据访问（主键即 request_id）。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("idempotent_request");
    }

    /** 插入首笔记录；request_id 重复键异常由上层捕获后比对指纹。 */
    public void insert(IdempotentRequest r) {
        var p = new HashMap<String, Object>();
        p.put("request_id", r.getRequestId());
        p.put("biz_type", r.getBizType());
        p.put("fingerprint", r.getFingerprint());
        p.put("http_status", r.getHttpStatus());
        p.put("response_body", r.getResponseBody());
        p.put("created_at", Timestamp.from(r.getCreatedAt()));
        insert.execute(p);
    }

    public Optional<IdempotentRequest> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    static final RowMapper<IdempotentRequest> MAPPER = (ResultSet rs, int rowNum) -> {
        IdempotentRequest r = new IdempotentRequest();
        r.setRequestId(rs.getString("request_id"));
        r.setBizType(rs.getString("biz_type"));
        r.setFingerprint(rs.getString("fingerprint"));
        r.setHttpStatus(rs.getInt("http_status"));
        r.setResponseBody(rs.getString("response_body"));
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };
}
