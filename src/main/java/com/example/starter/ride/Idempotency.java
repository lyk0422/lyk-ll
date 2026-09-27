package com.example.starter.ride;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等组件：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果，同键异参返回 409 且不改变设备状态；
 * 幂等记录与业务写入在同一事务内提交。
 */
public final class Idempotency {

    private Idempotency() {
    }

    /** 一次幂等执行的最终响应：HTTP 状态码 + JSON 响应体。 */
    public record ApiResult(int status, String body) {
    }

    /** 业务动作：在事务内执行并返回响应。 */
    @FunctionalInterface
    public interface Action {
        ApiResult run();
    }

    @Repository
    public static class Store {

        private final JdbcTemplate jdbc;

        public Store(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public Optional<Stored> find(String requestId) {
            return jdbc.query(
                            "SELECT fingerprint, response_status, response_body"
                                    + " FROM idempotency_record WHERE request_id = ?",
                            (rs, rowNum) -> new Stored(rs.getString(1),
                                    new ApiResult(rs.getInt(2), rs.getString(3))),
                            requestId)
                    .stream()
                    .findFirst();
        }

        public void insert(String requestId, long rideId, String operation,
                String fingerprint, ApiResult result) {
            jdbc.update("INSERT INTO idempotency_record"
                            + " (request_id, ride_id, operation, fingerprint, response_status, response_body)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    requestId, rideId, operation, fingerprint, result.status(), result.body());
        }

        record Stored(String fingerprint, ApiResult result) {
        }
    }

    @Service
    public static class Executor {

        private final Store store;
        private final TransactionTemplate tx;
        private final ObjectMapper objectMapper;

        public Executor(Store store, TransactionTemplate tx, ObjectMapper objectMapper) {
            this.store = store;
            this.tx = tx;
            this.objectMapper = objectMapper;
        }

        /**
         * 幂等执行：同键同参重放首次结果；同键异参抛 409；
         * 并发同键由主键约束兜底，失败方重读并按同参/异参处理。
         */
        public ApiResult execute(String requestId, String operation, long rideId,
                Object businessFields, Action action) {
            String fingerprint = fingerprint(requestId, businessFields);
            Optional<ApiResult> existing = findOrConflict(requestId, fingerprint);
            if (existing.isPresent()) {
                return existing.get();
            }
            try {
                return tx.execute(status -> {
                    ApiResult result = action.run();
                    store.insert(requestId, rideId, operation, fingerprint, result);
                    return result;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键：主键约束兜底，重读并按同参/异参处理
                return findOrConflict(requestId, fingerprint)
                        .orElseThrow(() -> ApiException.conflict("幂等记录冲突，请重试"));
            } catch (ApiException e) {
                // 并发同键重放：本事务因首个请求已改变状态而失败时，返回首次结果
                return findOrConflict(requestId, fingerprint).orElseThrow(() -> e);
            }
        }

        private Optional<ApiResult> findOrConflict(String requestId, String fingerprint) {
            Optional<Store.Stored> found = store.find(requestId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Store.Stored stored = found.get();
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "requestId已存在但业务参数不一致，拒绝重放");
            }
            return Optional.of(stored.result());
        }

        /**
         * 指纹 = SHA-256(结构化信封JSON)。
         * 信封将 requestId 作为JSON字符串独立编码、业务字段作为规范化JSON负载，
         * 键与负载边界无歧义：合法文本含分隔符（如 |、,、引号）也须区分为不同指纹。
         */
        public String fingerprint(String requestId, Object businessFields) {
            try {
                String payload = objectMapper.writeValueAsString(businessFields);
                String envelope = "{\"requestId\":"
                        + objectMapper.writeValueAsString(requestId)
                        + ",\"payload\":" + payload + "}";
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(envelope.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("请求体无法序列化", e);
            }
        }
    }
}
