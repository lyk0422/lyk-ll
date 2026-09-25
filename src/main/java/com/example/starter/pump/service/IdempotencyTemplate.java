package com.example.starter.pump.service;

import com.example.starter.pump.core.TimeProvider;
import com.example.starter.pump.domain.StoredRequest;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.IdempotentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等执行模板：以 requestId 为键，指纹覆盖 requestId 与全部业务字段。
 *
 * <p>语义：
 * <ul>
 *   <li>同键同参：返回首次结果（重放），不重复执行业务；</li>
 *   <li>同键异参：抛 409，不执行任何业务，不改变泵站状态；</li>
 *   <li>首次：占位抢占成功后执行业务并回写首次结果，与业务处于同一事务。</li>
 * </ul>
 */
@Component
public class IdempotencyTemplate {

    private final IdempotentRepository repository;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;

    public IdempotencyTemplate(IdempotentRepository repository, ObjectMapper objectMapper,
                               TimeProvider timeProvider) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
    }

    /** 幂等执行结果：value 为业务返回体，replayed 标记本次是否命中同键重放。 */
    public record Outcome<T>(T value, boolean replayed) {
    }

    /**
     * 幂等执行业务并返回结果（首次或重放）。
     *
     * @param requestId     幂等键
     * @param bizType       业务类型 WARNING/PUMP_TASK/ARCHIVE
     * @param fingerprint   requestId+全部业务字段的指纹
     * @param successStatus 首次受理HTTP状态码
     * @param action        首次受理时执行的业务
     * @param resultType    结果类型，用于重放反序列化
     */
    public <T> Outcome<T> execute(String requestId, String bizType, String fingerprint,
                                  int successStatus, Supplier<T> action, Class<T> resultType) {
        Optional<StoredRequest> existing = repository.find(requestId);
        if (existing.isPresent()) {
            return new Outcome<>(replayOrConflict(existing.get(), fingerprint, resultType), true);
        }
        boolean acquired = repository.tryAcquire(requestId, bizType, fingerprint,
                timeProvider.now());
        if (!acquired) {
            StoredRequest concurrent = repository.find(requestId)
                    .orElseThrow(() -> new IllegalStateException("幂等占位冲突但重查为空: " + requestId));
            return new Outcome<>(replayOrConflict(concurrent, fingerprint, resultType), true);
        }
        T result = action.get();
        repository.saveResult(requestId, successStatus, writeJson(result));
        return new Outcome<>(result, false);
    }

    private <T> T replayOrConflict(StoredRequest stored, String fingerprint, Class<T> resultType) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId=" + stored.requestId()
                    + " 已用于不同业务参数，拒绝受理且不改变泵站状态");
        }
        if (stored.responseBody() == null) {
            throw new IllegalStateException("幂等记录缺少首次结果: " + stored.requestId());
        }
        try {
            return objectMapper.readValue(stored.responseBody(), resultType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等首次结果反序列化失败: " + stored.requestId(), e);
        }
    }

    private String writeJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等首次结果序列化失败", e);
        }
    }
}
