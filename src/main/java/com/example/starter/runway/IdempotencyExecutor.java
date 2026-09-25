package com.example.starter.runway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 幂等执行器：在同一事务内完成“查重→执行业务→登记幂等记录”。
 * 指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参返回 409。
 */
@Component
public class IdempotencyExecutor {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行：已存在同键记录时按指纹决定重放或 409，否则执行业务并登记。
     */
    @Transactional
    public <T> T execute(String scope, String requestId, String fingerprint, Supplier<T> action, Class<T> type) {
        var existing = repository.find(scope, requestId);
        if (existing.isPresent()) {
            IdempotencyRepository.IdemRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 的业务参数不一致");
            }
            return read(row.responseBody(), type);
        }
        T result = action.get();
        repository.insert(scope, requestId, fingerprint, write(result));
        return result;
    }

    /**
     * 并发冲突后的重放尝试（原事务已回滚，此处新连接只读）：
     * 无幂等记录返回 null；有记录且指纹一致返回首次结果；指纹不一致抛 409。
     */
    public <T> T tryReplay(String scope, String requestId, String fingerprint, Class<T> type) {
        var existing = repository.find(scope, requestId);
        if (existing.isEmpty()) {
            return null;
        }
        IdempotencyRepository.IdemRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同 requestId 的业务参数不一致");
        }
        return read(row.responseBody(), type);
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }
}
