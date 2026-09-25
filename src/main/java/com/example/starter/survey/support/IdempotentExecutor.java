package com.example.starter.survey.support;

import com.example.starter.survey.data.IdempotencyRepository;
import com.example.starter.survey.domain.IdempotentRecord;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.dto.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;

/**
 * 幂等执行器：所有写操作在同一数据库事务内完成“指纹校验 → 占键 → 业务 → 回填结果”。
 * <ul>
 *   <li>同键同参：重放首次结果（{@link ApiResult#replayed} 为 true）；</li>
 *   <li>同键异参：409，不执行业务、不产生新证书；</li>
 *   <li>业务异常：整体回滚（含占键记录），重放将重新得到同样的业务结果；</li>
 *   <li>并发同键：唯一索引保证只有一个首插成功，落败方读取已提交结果重放。</li>
 * </ul>
 */
@Component
public class IdempotentExecutor {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;

    public IdempotentExecutor(IdempotencyRepository repository,
                              ObjectMapper objectMapper,
                              TimeProvider timeProvider) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
    }

    /**
     * 在事务内以幂等方式执行业务动作。
     *
     * @param requestId   幂等键
     * @param fingerprint 业务指纹（requestId + 操作类型 + 全部业务字段）
     * @param resultType  成功结果类型，用于重放时反序列化首次响应
     * @param action       首次受理时执行的业务动作
     */
    @Transactional
    public <T> ApiResult<T> run(String requestId, String fingerprint,
                                Class<T> resultType, SupplierWithExcept<T> action) {
        // 最多两次：极端并发下对方事务回滚、占键消失后可重新抢占。
        for (int attempt = 0; attempt < 2; attempt++) {
            var existing = repository.find(requestId);
            if (existing.isPresent()) {
                return replayOrConflict(requestId, fingerprint, existing.get(), resultType);
            }
            if (!repository.reserve(requestId, fingerprint, timeProvider.now())) {
                continue;
            }
            T result;
            try {
                result = action.get();
            } catch (RuntimeException ex) {
                // 抛出后事务回滚，占键与业务写入一并撤销。
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw ex;
            }
            String body;
            try {
                body = objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                throw new IllegalStateException("响应序列化失败", e);
            }
            repository.complete(requestId, 200, body);
            return ApiResult.first(result, requestId);
        }
        throw new ConflictException("IDEMPOTENCY_RACE",
                "幂等键并发处理异常，请稍后按相同参数重放: " + requestId);
    }

    private <T> ApiResult<T> replayOrConflict(String requestId, String fingerprint,
                                              IdempotentRecord record, Class<T> resultType) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "requestId 已用于不同的业务请求: " + requestId);
        }
        if (record.httpStatus() == IdempotencyRepository.STATUS_PENDING) {
            // 另一事务仍在处理：直接按冲突返回，提示稍后用相同参数重放，不重复执行业务。
            throw new ConflictException("IDEMPOTENCY_IN_FLIGHT",
                    "相同 requestId 的请求正在处理中: " + requestId);
        }
        try {
            T data = objectMapper.readValue(record.responseBody(), resultType);
            return ApiResult.replay(data, requestId);
        } catch (Exception e) {
            throw new IllegalStateException("历史响应反序列化失败", e);
        }
    }

    /** 允许动作抛出受检异常包装为非受检的函数式接口。 */
    @FunctionalInterface
    public interface SupplierWithExcept<T> {
        T get();
    }

    /** 当前时间便捷方法（供服务构造指纹时间戳场景统一使用）。 */
    public Instant now() {
        return timeProvider.now();
    }
}
