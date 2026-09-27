package com.example.starter.ride;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等组件：指纹为长度前缀的结构化 SHA-256，覆盖 requestId 与全部业务字段
 * （含版本、集合与精确时刻）。合法文本即使含分隔符（如 "a|b" 与 ["a","b"]）也可区分。
 * 同键同参返回首次结果，同键异参返回 409 且不改变设备状态；失败不占用 requestId 键；
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

        public Executor(Store store, TransactionTemplate tx) {
            this.store = store;
            this.tx = tx;
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
         * 结构化长度前缀指纹：SHA-256(规范化结构)。
         * 对 record 按“类名 + 字段数 + 每字段‘长度:值’”编码，集合加元素数，
         * 标量按类型标记编码；合法文本即使含分隔符也不会与其他结构碰撞。
         */
        public String fingerprint(String requestId, Object businessFields) {
            try {
                StringBuilder out = new StringBuilder();
                appendTyped(out, requestId);
                appendValue(out, businessFields);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(out.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void appendValue(StringBuilder out, Object value) {
            if (value == null) {
                out.append("n:0:");
            } else if (value instanceof Record record) {
                Object[] components = extractComponents(record);
                out.append("R:").append(record.getClass().getSimpleName())
                        .append(':').append(components.length).append(':');
                for (Object component : components) {
                    appendValue(out, component);
                }
            } else if (value instanceof List<?> list) {
                out.append("L:").append(list.size()).append(':');
                for (Object element : list) {
                    appendNullable(out, element);
                }
            } else if (value instanceof String text) {
                appendTyped(out, text);
            } else if (value instanceof Boolean b) {
                out.append("b:").append(b).append(':');
            } else if (value instanceof Instant instant) {
                out.append("i:").append(instant.toEpochMilli()).append(':');
            } else if (value instanceof Number number) {
                out.append("num:").append(value.getClass().getSimpleName())
                        .append(':').append(number).append(':');
            } else if (value instanceof Enum<?> e) {
                out.append("e:").append(e.name()).append(':');
            } else {
                throw new IllegalArgumentException(
                        "幂等指纹不支持的字段类型：" + value.getClass());
            }
        }

        private static void appendNullable(StringBuilder out, Object element) {
            if (element == null) {
                out.append("n:0:");
            } else {
                appendValue(out, element);
            }
        }

        private static Object[] extractComponents(Record record) {
            try {
                return java.util.Arrays.stream(record.getClass().getRecordComponents())
                        .map(component -> {
                            try {
                                return component.getAccessor().invoke(record);
                            } catch (ReflectiveOperationException e) {
                                throw new IllegalStateException("读取记录字段失败", e);
                            }
                        })
                        .toArray();
            } catch (SecurityException e) {
                throw new IllegalStateException("读取记录字段失败", e);
            }
        }

        /** 字符串字段：s + UTF-8 字节数 + 内容，长度前缀保证含分隔符文本不产生拼接歧义。 */
        private static void appendTyped(StringBuilder out, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.append("s:").append(bytes.length).append(':').append(text).append(':');
        }
    }
}
