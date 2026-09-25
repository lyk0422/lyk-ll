package com.example.starter.dye.service;

import com.example.starter.dye.domain.IdempotentRequestPO;
import com.example.starter.dye.domain.OperationType;
import com.example.starter.dye.repo.IdempotencyRepository;
import com.example.starter.dye.support.FingerprintUtil;
import com.example.starter.dye.web.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * requestId 幂等执行模板。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>同键同参：返回首次结果（含首次的 HTTP 状态码，例如投料超差的 422 也可重放）；</li>
 *   <li>同键异参：抛 409，业务不执行、库存不扣减（占位行随事务回滚）；</li>
 *   <li>指纹覆盖 operation + requestId + 全部业务字段。</li>
 * </ul>
 * 业务动作与占位登记、结果回填在同一事务内：抛出的异常会整体回滚，不留半条登记
 * （因此“缸次不存在/状态不符”这类依赖外部状态的错误不占幂等键，可稍后重试）；
 * 而确定性的业务拒绝（如称量超差）以 {@link ServiceResult} 携带 422 正常提交并缓存。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 业务动作的结果：携带需要缓存/重放的 HTTP 状态码与响应体。
     */
    public record ServiceResult<T>(int statusCode, T body) {

        public static <T> ServiceResult<T> ok(T body) {
            return new ServiceResult<>(200, body);
        }

        public static <T> ServiceResult<T> of(int statusCode, T body) {
            return new ServiceResult<>(statusCode, body);
        }
    }

    /**
     * 幂等执行结果。
     *
     * @param replay true 表示本次为重放
     * @param statusCode 首次结果或重放结果的 HTTP 状态码（两种路径一致）
     * @param cachedJson 重放时返回首次结果 JSON；首次执行为 null
     * @param body 首次执行得到的业务结果；重放时为 null
     */
    public record Outcome<T>(boolean replay, int statusCode, String cachedJson, T body) {

        public static <T> Outcome<T> first(ServiceResult<T> result) {
            return new Outcome<>(false, result.statusCode(), null, result.body());
        }

        public static <T> Outcome<T> replay(int status, String cachedJson) {
            return new Outcome<>(true, status, cachedJson, null);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public <T> Outcome<T> execute(OperationType operation, String requestId, Object payload,
                                  Supplier<ServiceResult<T>> action) {
        String fingerprint = FingerprintUtil.fingerprint(operation.name(), requestId, payload);
        Optional<IdempotentRequestPO> existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, requestId);
        }
        // 并发同键：插入占位；若对方事务未提交则等待其提交后重放；
        // 若对方事务回滚导致占位消失，则本轮重试插入（最多 3 轮）。
        for (int round = 0; round < 3; round++) {
            try {
                repository.insertPlaceholder(requestId, operation, fingerprint, LocalDateTime.now(clock));
                break;
            } catch (DuplicateKeyException concurrent) {
                IdempotentRequestPO committed = awaitCommitted(requestId);
                if (committed != null) {
                    return replayOrConflict(committed, fingerprint, requestId);
                }
                // 占位行消失（对方回滚），重试插入
            }
        }
        ServiceResult<T> result = action.get();
        repository.fillResult(requestId, result.statusCode(), toJson(result.body()));
        return Outcome.first(result);
    }

    /**
     * 等待先到事务提交并返回其登记行；约 1 秒内仍不可见则返回 null（视为对方回滚）。
     */
    private IdempotentRequestPO awaitCommitted(String requestId) {
        for (int i = 0; i < 50; i++) {
            var found = repository.find(requestId);
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("requestId=" + requestId + " 并发受理等待被中断，请重试");
            }
        }
        return null;
    }

    private <T> Outcome<T> replayOrConflict(IdempotentRequestPO po, String fingerprint, String requestId) {
        if (!po.getFingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId=" + requestId + " 已用于不同业务参数，拒绝受理");
        }
        return Outcome.replay(po.getStatusCode(), po.getResponseClob());
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }
}
