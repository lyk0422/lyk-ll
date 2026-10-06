package com.example.starter.service;

import java.util.function.Supplier;

import com.example.starter.support.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 写操作幂等服务：按 requestId 幂等，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参抛出 409 冲突。
 * 须在业务事务内调用，与业务写入同一事务提交。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行写操作。
     *
     * @param requestId   幂等请求 ID
     * @param fingerprint 覆盖 requestId 与全部业务字段的指纹
     * @param type        响应体类型（用于回放反序列化）
     * @param action      首次执行的业务动作
     * @return 首次执行结果；同键同参时返回首次存储的结果
     */
    public <T> T execute(String requestId, String fingerprint, Class<T> type, Supplier<T> action) {
        IdemRow existing = find(requestId);
        if (existing != null) {
            return replay(existing, fingerprint, type);
        }
        try {
            jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint) VALUES (?, ?)",
                    requestId, fingerprint);
        } catch (DuplicateKeyException e) {
            // 并发同键：等待对方事务提交后读取其首次结果
            IdemRow concurrent = find(requestId);
            if (concurrent == null) {
                throw new ConflictException("幂等记录写入冲突，请重试");
            }
            return replay(concurrent, fingerprint, type);
        }
        T result = action.get();
        jdbc.update("UPDATE idempotency_record SET http_status = 200, response_body = ? WHERE request_id = ?",
                toJson(result), requestId);
        return result;
    }

    private IdemRow find(String requestId) {
        return jdbc.query("SELECT fingerprint, response_body FROM idempotency_record WHERE request_id = ?",
                rs -> rs.next() ? new IdemRow(rs.getString(1), rs.getString(2)) : null,
                requestId);
    }

    private <T> T replay(IdemRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务参数不一致，拒绝执行");
        }
        if (row.responseBody() == null) {
            throw new ConflictException("相同 requestId 的请求正在处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(row.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等回放反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }

    private record IdemRow(String fingerprint, String responseBody) {
    }
}
