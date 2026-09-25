package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.model.IdempotencyRecord;
import com.example.starter.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果快照，同键异参抛 409 且不改变业务状态。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository repository,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 以 requestId 为幂等键执行业务动作。
     *
     * @param apiName      接口名称，参与指纹计算
     * @param requestId    幂等键
     * @param businessData 全部业务字段（按声明顺序拼接进指纹）
     * @param responseType 响应类型
     * @param action       业务动作，在事务内执行并持久化响应快照
     * @return 首次执行结果或重放的首次结果
     */
    public <T> T execute(String apiName, String requestId, Object[] businessData,
                         Class<T> responseType, Supplier<T> action) {
        String fingerprint = fingerprint(apiName, requestId, businessData);
        Optional<IdempotencyRecord> existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, responseType);
        }
        try {
            return transactionTemplate.execute(status -> {
                T result = action.get();
                repository.insert(requestId, apiName, fingerprint, writeJson(result),
                        Instant.now(clock));
                return result;
            });
        } catch (DuplicateKeyException duplicate) {
            // 并发同键：本事务已回滚，重读已提交的首次记录并校验指纹。
            IdempotencyRecord committed = repository.findByRequestId(requestId)
                    .orElseThrow(() -> duplicate);
            return replay(committed, fingerprint, responseType);
        }
    }

    private <T> T replay(IdempotencyRecord record, String fingerprint, Class<T> responseType) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 携带了不同的业务参数，请求被拒绝且未改变调度状态");
        }
        try {
            return objectMapper.readValue(record.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照反序列化失败", e);
        }
    }

    private String fingerprint(String apiName, String requestId, Object[] businessData) {
        StringBuilder raw = new StringBuilder(apiName).append('|').append(requestId);
        for (Object field : businessData) {
            raw.append('|').append(String.valueOf(field));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照序列化失败", e);
        }
    }
}
