package com.example.starter.service;

import java.util.List;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等受理：同一 scope 下 requestId 唯一。
 * 同键同参返回首次受理结果；同键异参返回 409 且不改变业务状态；
 * 并发同键由主键约束仲裁，失败方回读后按首次结果返回。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    private record Stored(int status, String fingerprint, String body) {
    }

    /**
     * 在单事务内执行业务动作并落幂等记录；返回首次结果的 JSON 报文。
     *
     * @param scope       业务边界（REPORT/DISPATCH/CLOSE/MAINTENANCE）
     * @param requestId   幂等键
     * @param fingerprint 覆盖 requestId 与全部业务字段的指纹
     * @param business    业务动作，返回将被序列化为响应报文的对象
     */
    public ResponseEntity<String> handle(String scope, String requestId, String fingerprint,
                                         Supplier<Object> business) {
        Stored existing = find(scope, requestId);
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        try {
            return tx.execute(status -> {
                Object result = business.get();
                String body = toJson(result);
                jdbc.update("INSERT INTO idempotency_record(scope, request_id, fingerprint, response_status, response_body) "
                                + "VALUES (?,?,?,?,?)",
                        scope, requestId, fingerprint, 200, body);
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：本事务已回滚，回读胜出方的首次结果
            Stored winner = find(scope, requestId);
            if (winner != null) {
                return replay(winner, fingerprint);
            }
            // 非幂等键唯一约束冲突（如电梯进行中救援占坑位）
            throw ApiException.conflict("STATE_CONFLICT", "并发请求冲突：同一电梯已存在进行中的救援单或重复提交");
        }
    }

    private ResponseEntity<String> replay(Stored stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 提交了不同的业务参数，已拒绝且未改变状态");
        }
        return ResponseEntity.status(stored.status()).contentType(MediaType.APPLICATION_JSON).body(stored.body());
    }

    private Stored find(String scope, String requestId) {
        List<Stored> rows = jdbc.query(
                "SELECT response_status, fingerprint, response_body FROM idempotency_record WHERE scope=? AND request_id=?",
                (rs, i) -> new Stored(rs.getInt(1), rs.getString(2), rs.getString(3)),
                scope, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 是否已存在该键的受理记录（业务预检用，并发同键时避免误判状态冲突）。 */
    public boolean exists(String scope, String requestId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE scope=? AND request_id=?",
                Integer.class, scope, requestId);
        return n != null && n > 0;
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
