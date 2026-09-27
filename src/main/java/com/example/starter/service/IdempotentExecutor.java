package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.BatchRepository.IdempotencyRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 幂等受理执行器：指纹基于 requestId 与请求对象的结构化 JSON（List 序列化为数组、
 * 字符串分隔符经 JSON 转义），合法文本含分隔符也无歧义；幂等登记与业务写入同事务，
 * 业务失败时不占键。同键同参重放首次冻结结果，同键异参返回 409。
 */
@Component
public class IdempotentExecutor {

    private final BatchRepository repo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotentExecutor(BatchRepository repo, ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行业务并登记幂等结果。
     *
     * @param endpoint  受理接口标识
     * @param requestId 调用方请求标识（全局唯一）
     * @param request   完整请求对象（含版本、集合与精确时刻）
     * @param type      响应类型，用于重放反序列化
     * @param action    业务动作
     */
    public <T> T run(String endpoint, String requestId, Object request, Class<T> type,
                     Supplier<T> action) {
        String fingerprint = fingerprint(requestId, request);
        Optional<IdempotencyRow> existing = repo.findIdempotency(requestId);
        if (existing.isPresent()) {
            IdempotencyRow record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("相同 requestId 携带不同业务参数，拒绝受理");
            }
            return readValue(record.responseBody(), type);
        }
        try {
            T result = action.get();
            repo.insertIdempotency(requestId, endpoint, fingerprint, writeValue(result),
                    Instant.now(clock).toString());
            return result;
        } catch (DuplicateKeyException e) {
            Optional<IdempotencyRow> concurrent = repo.findIdempotency(requestId);
            if (concurrent.isPresent() && concurrent.get().fingerprint().equals(fingerprint)) {
                return readValue(concurrent.get().responseBody(), type);
            }
            throw ApiException.conflict("并发受理冲突：请求标识已被其他参数占用");
        } catch (ApiException e) {
            // 行锁串行后，同键同参并发可能先撞到业务冲突（如绑定已存在、版本已推进）：
            // 此时首个事务应已提交幂等登记，复查命中则重放首次冻结结果，否则原样抛出。
            if (e.status() == 409) {
                Optional<IdempotencyRow> concurrent = repo.findIdempotency(requestId);
                if (concurrent.isPresent() && concurrent.get().fingerprint().equals(fingerprint)) {
                    return readValue(concurrent.get().responseBody(), type);
                }
            }
            throw e;
        }
    }

    /** 结构化指纹：requestId 换行前缀 + 请求对象规范化 JSON 后 SHA-256 十六进制。 */
    public String fingerprint(String requestId, Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((requestId + "\n" + json).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹计算失败", e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }
}
