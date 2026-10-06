package com.example.starter.service;

import com.example.starter.error.ApiException;
import com.example.starter.model.IdempotencyRow;
import com.example.starter.repository.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写操作幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409；幂等记录与业务写入同事务。
 */
@Service
public class IdempotencyService {

    private final TransactionTemplate transactionTemplate;
    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(PlatformTransactionManager transactionManager,
                              IdempotencyRepository repository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在单事务内执行写操作：先占位幂等记录，再执行业务，最后回填响应快照。
     * 并发下同键插入冲突时回滚本事务并重放已提交的首次结果。
     */
    public <T> T execute(String endpoint, String requestId, Object businessPayload,
                         Class<T> responseType, Supplier<T> action) {
        try {
            return transactionTemplate.execute(status ->
                    doExecute(endpoint, requestId, businessPayload, responseType, action));
        } catch (DuplicateKeyException duplicate) {
            return repository.find(requestId)
                    .map(row -> replay(row, endpoint, requestId, businessPayload, responseType))
                    .orElseThrow(() -> duplicate);
        }
    }

    private <T> T doExecute(String endpoint, String requestId, Object businessPayload,
                            Class<T> responseType, Supplier<T> action) {
        var existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), endpoint, requestId, businessPayload, responseType);
        }
        repository.insert(requestId, endpoint,
                fingerprint(endpoint, requestId, businessPayload), LocalDateTime.now(clock));
        T result = action.get();
        repository.updateResponse(requestId, writeJson(result));
        return result;
    }

    private <T> T replay(IdempotencyRow row, String endpoint, String requestId,
                         Object businessPayload, Class<T> responseType) {
        String expected = fingerprint(endpoint, requestId, businessPayload);
        if (!row.endpoint().equals(endpoint) || !row.fingerprint().equals(expected)) {
            throw ApiException.conflict("requestId 已使用且业务参数不一致");
        }
        if (row.responseBody() == null) {
            throw ApiException.conflict("requestId 对应的请求正在处理中");
        }
        try {
            return objectMapper.readValue(row.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照解析失败", e);
        }
    }

    private String fingerprint(String endpoint, String requestId, Object businessPayload) {
        String canonical = endpoint + "\n" + requestId + "\n" + writeJson(businessPayload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求序列化失败", e);
        }
    }
}
