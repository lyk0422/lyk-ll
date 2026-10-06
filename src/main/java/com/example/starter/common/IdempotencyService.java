package com.example.starter.common;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等写执行器：同一事务内先落幂等记录（主键占位）再执行业务并回写首次响应。
 * 同键同参返回首次结果；同键异参抛 409；业务失败整体回滚，允许修正后重试。
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 以 requestId 为幂等键执行写操作。
     *
     * @param requestId    请求标识，非空
     * @param fingerprint  覆盖 requestId 与全部业务字段的指纹
     * @param responseType 响应类型，用于首次结果回放反序列化
     * @param action       业务动作，与幂等记录同事务提交
     */
    public <T> T execute(String requestId, String fingerprint, Class<T> responseType, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        try {
            return txTemplate.execute(status -> {
                jdbc.sql("INSERT INTO idempotency_record(request_id, fingerprint, http_status, response_body, created_at)"
                                + " VALUES (?, ?, 200, NULL, ?)")
                        .params(requestId, fingerprint, LocalDateTime.now(clock))
                        .update();
                T result = action.get();
                jdbc.sql("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?")
                        .params(writeJson(result), requestId)
                        .update();
                return result;
            });
        } catch (DuplicateKeyException | CannotAcquireLockException e) {
            return resolveExisting(requestId, fingerprint, responseType, e);
        }
    }

    /**
     * 主键冲突或锁超时后读取已提交的幂等记录：同参返回首次结果，异参 409；
     * 记录不存在说明冲突来自业务唯一约束（如路段执行中许可），按并发冲突处理。
     */
    private <T> T resolveExisting(String requestId, String fingerprint, Class<T> responseType, RuntimeException cause) {
        List<StoredRow> rows = jdbc.sql("SELECT fingerprint, response_body FROM idempotency_record WHERE request_id = ?")
                .param(requestId)
                .query((rs, i) -> new StoredRow(rs.getString(1), rs.getString(2)))
                .list();
        if (rows.isEmpty()) {
            throw ApiException.conflict("并发写入冲突，请重试");
        }
        StoredRow row = rows.get(0);
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("相同 requestId 提交了不同的业务参数");
        }
        if (row.responseBody() == null) {
            throw ApiException.conflict("相同请求正在处理中，请稍后重试");
        }
        return readJson(row.responseBody(), responseType);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private record StoredRow(String fingerprint, String responseBody) {
    }
}
