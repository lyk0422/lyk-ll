package com.example.starter.core;

import com.example.starter.api.dto.IdempotentEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * requestId 幂等支撑的 JDBC 实现。
 *
 * <p>受理流程：先插入“占位”记录抢占 (scope, requestId) 主键，再在同一事务内执行业务，
 * 成功后写回首次响应快照。并发同键由主键约束保证唯一受理；同键同参重放快照，
 * 同键异参返回 409 且不触碰执行参数。业务异常随事务回滚，占位记录一并释放。</p>
 */
@Service
public class JdbcIdempotencyService implements IdempotencyService {

    private static final int IN_FLIGHT_WAIT_MILLIS = 3000;
    private static final long SPIN_SLEEP_MILLIS = 20L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcIdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public <T> IdempotentEnvelope<T> execute(String scope, String requestId, String fingerprint,
                                             Class<T> resultType, Supplier<T> supplier) {
        long waitDeadline = clock.millis() + IN_FLIGHT_WAIT_MILLIS;
        for (;;) {
            Map<String, Object> existing = findRecord(scope, requestId);
            if (existing == null) {
                if (!claim(scope, requestId, fingerprint)) {
                    // 并发同键恰在此刻被他人抢占，重读其记录。
                    continue;
                }
                T result = supplier.get();
                jdbcTemplate.update("""
                        UPDATE idempotency_record SET response_json = ?
                        WHERE scope = ? AND request_id = ?
                        """, toJson(result), scope, requestId);
                return new IdempotentEnvelope<>(result, false);
            }
            if (!fingerprint.equals(existing.get("fingerprint"))) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_CONFLICT",
                        "requestId=" + requestId + " 已用于不同业务参数，拒绝重复受理");
            }
            String json = (String) existing.get("response_json");
            if (json != null && !json.isEmpty()) {
                return new IdempotentEnvelope<>(fromJson(json, resultType), true);
            }
            if (clock.millis() >= waitDeadline) {
                throw ApiException.conflict("IDEMPOTENCY_IN_FLIGHT",
                        "requestId=" + requestId + " 的首次受理仍在进行中，请稍后重试");
            }
            try {
                Thread.sleep(SPIN_SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待同键首次受理时被中断", e);
            }
        }
    }

    /**
     * 占位抢占；并发同键唯一约束拒绝后来者并返回 false，由调用方重读赢家记录。
     */
    private boolean claim(String scope, String requestId, String fingerprint) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO idempotency_record (scope, request_id, fingerprint, response_json, created_at)
                    VALUES (?, ?, ?, '', ?)
                    """, scope, requestId, fingerprint, Timestamp.valueOf(LocalDateTime.now(clock)));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Map<String, Object> findRecord(String scope, String requestId) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT fingerprint, response_json FROM idempotency_record WHERE scope = ? AND request_id = ?",
                    scope, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化首次受理结果", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法反序列化首次受理结果", e);
        }
    }
}
