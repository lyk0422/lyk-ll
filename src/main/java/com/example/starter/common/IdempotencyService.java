package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 幂等组件：写操作按 requestId 幂等。
 * 指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果快照，同键异参抛 409。
 * 幂等行与业务写入处于同一事务：业务失败回滚时幂等行一并回滚。
 */
@Component
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行幂等写操作。
     *
     * @param requestId   幂等请求号
     * @param fingerprint 请求指纹（见 {@link #fingerprint}）
     * @param type        结果类型（用于快照反序列化）
     * @param action      首次执行的业务动作
     * @return 首次执行结果；同键同参重放时返回首次结果
     */
    public <T> T execute(String requestId, String fingerprint, Class<T> type, Supplier<T> action) {
        try {
            jdbc.sql("INSERT INTO idempotency_key (request_id, fingerprint, result_json, created_at)"
                            + " VALUES (:requestId, :fingerprint, NULL, :createdAt)")
                    .param("requestId", requestId)
                    .param("fingerprint", fingerprint)
                    .param("createdAt", Timestamp.from(clock.instant()))
                    .update();
        } catch (DuplicateKeyException e) {
            return replay(requestId, fingerprint, type);
        }
        T result = action.get();
        jdbc.sql("UPDATE idempotency_key SET result_json = :json WHERE request_id = :requestId")
                .param("json", toJson(result))
                .param("requestId", requestId)
                .update();
        return result;
    }

    private <T> T replay(String requestId, String fingerprint, Class<T> type) {
        var row = jdbc.sql("SELECT fingerprint, result_json FROM idempotency_key WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query((rs, i) -> new StoredRow(rs.getString("fingerprint"), rs.getString("result_json")))
                .single();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在但业务参数不一致");
        }
        if (row.resultJson() == null) {
            throw ApiException.conflict("相同请求正在处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(row.resultJson(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果快照反序列化失败", e);
        }
    }

    /**
     * 计算请求指纹：requestId 与全部业务字段拼接后的 SHA-256。
     */
    public static String fingerprint(String requestId, Object... businessFields) {
        String canonical = Stream.concat(Stream.of(requestId),
                        Stream.of(businessFields).map(f -> f == null ? "<null>" : String.valueOf(f)))
                .collect(Collectors.joining("|"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果快照序列化失败", e);
        }
    }

    private record StoredRow(String fingerprint, String resultJson) {
    }
}
