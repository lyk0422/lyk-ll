package com.example.starter.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 写操作幂等组件。
 *
 * <p>指纹覆盖 requestId 与全部业务字段：同一 requestId 且指纹一致时返回首次结果；
 * 同一 requestId 指纹不同返回 409。记录在业务事务内先占位插入，依赖主键约束
 * 保证并发下仅一个请求真正执行业务逻辑，其余请求读取首次提交的结果。</p>
 */
@Component
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：SHA-256(requestId + 全部业务字段按声明顺序拼接)。
     *
     * @param requestId 幂等请求ID
     * @param businessFields 全部业务字段，顺序固定
     * @return 十六进制指纹
     */
    public String fingerprint(String requestId, Object... businessFields) {
        StringBuilder raw = new StringBuilder(requestId == null ? "" : requestId);
        for (Object field : businessFields) {
            raw.append('|').append(String.valueOf(field));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 在调用方事务内执行幂等写操作。
     *
     * @param endpoint    接口标识
     * @param requestId   幂等请求ID
     * @param fingerprint 请求指纹
     * @param type        返回类型
     * @param action      业务动作，仅首次执行
     * @return 首次执行结果（重放时返回首次结果）
     */
    public <T> T execute(String endpoint, String requestId, String fingerprint, Class<T> type,
                         Supplier<T> action) {
        try {
            jdbc.sql("INSERT INTO idempotency_record (request_id, endpoint, fingerprint) VALUES (?, ?, ?)")
                    .params(requestId, endpoint, fingerprint)
                    .update();
        } catch (DuplicateKeyException e) {
            return replay(requestId, fingerprint, type);
        }
        T result = action.get();
        String body = serialize(result);
        jdbc.sql("UPDATE idempotency_record SET response_status = 200, response_body = ? WHERE request_id = ?")
                .params(body, requestId)
                .update();
        return result;
    }

    private <T> T replay(String requestId, String fingerprint, Class<T> type) {
        Optional<StoredRecord> stored = jdbc.sql(
                        "SELECT fingerprint, response_body FROM idempotency_record WHERE request_id = ?")
                .param(requestId)
                .query((rs, rowNum) -> new StoredRecord(rs.getString(1), rs.getString(2)))
                .optional();
        if (stored.isEmpty() || stored.get().responseBody() == null) {
            throw BusinessException.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "相同 requestId 的请求正在处理中，请稍后重试");
        }
        if (!stored.get().fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但业务参数不一致");
        }
        return deserialize(stored.get().responseBody(), type);
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private record StoredRecord(String fingerprint, String responseBody) {
    }
}
