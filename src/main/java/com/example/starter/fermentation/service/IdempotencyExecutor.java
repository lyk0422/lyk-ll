package com.example.starter.fermentation.service;

import com.example.starter.fermentation.DomainException;
import com.example.starter.fermentation.repo.IdempotencyRepository;
import com.example.starter.fermentation.repo.IdempotencyRepository.IdempotentRow;
import com.example.starter.fermentation.repo.IdempotencyRepository.IdempotentRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等执行支撑：同键同参回放首次结果，同键异参返回 409 且不产生业务变更。
 * 首次结果以 JSON 快照落 idempotent_request；并发同键靠主键冲突后重读判定，败者事务整体回滚。
 */
@Component
public class IdempotencyExecutor {

    private final IdempotencyRepository idempotencyRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyExecutor(IdempotencyRepository idempotencyRepository,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 执行业务核心：先查重放/冲突；无记录时在单事务内跑业务并落首次响应快照。
     *
     * @param requestId    幂等键
     * @param requestType  请求类型
     * @param fingerprint  覆盖 requestId 与全部业务字段的指纹
     * @param responseType 响应类型，用于回放反序列化
     * @param core         事务内业务逻辑，返回响应体与所属批次 id
     */
    public <T> T execute(String requestId,
                         String requestType,
                         String fingerprint,
                         Class<T> responseType,
                         BusinessCore<T> core) {
        var existing = idempotencyRepository.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, responseType);
        }
        CoreResult<T> result;
        try {
            result = transactionTemplate.execute(status -> {
                T body = core.run();
                Long batchId = core.assignedBatchId();
                idempotencyRepository.insert(requestId, requestType, fingerprint,
                        200, toJson(body), batchId);
                return new CoreResult<>(body);
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：败者事务已回滚，胜者已提交；重读后回放或判冲突。
            IdempotentRow winner = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> new DomainException(409, "conflicting concurrent request: " + requestId));
            return replayOrConflict(winner, fingerprint, responseType);
        }
        return result.body();
    }

    private <T> T replayOrConflict(IdempotentRow row, String fingerprint, Class<T> responseType) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new DomainException(409,
                    "idempotent request reused with different business parameters: " + row.requestId());
        }
        try {
            return objectMapper.readValue(row.responseJson(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("failed to replay idempotent response", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("failed to snapshot idempotent response", e);
        }
    }

    /**
     * 事务内业务核心，assignedBatchId 用于幂等记录关联批次。
     */
    public interface BusinessCore<T> {

        T run();

        default Long assignedBatchId() {
            return null;
        }
    }

    private record CoreResult<T>(T body) {
    }
}
