package com.example.starter.lc.support;

import com.example.starter.lc.LcBusinessException;
import com.example.starter.lc.LcErrorCode;
import com.example.starter.lc.OperationType;
import com.example.starter.lc.dto.ErrorResponse;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.repo.IdempotencyRepository;
import com.example.starter.lc.repo.IdempotentRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * requestId 幂等执行模板：
 * 首次请求写入“处理中”占位并执行业务；同键同参重放返回首次响应；同键异参返回 409 且不触碰业务数据。
 */
@Component
public class IdempotentTemplate {

    /** 并发同键请求等待首次处理完成的轮询上限（50 × 100ms = 5s）。 */
    private static final int WAIT_ROUNDS = 50;

    private static final Duration WAIT_STEP = Duration.ofMillis(100);

    private final IdempotencyRepository idempotencyRepository;

    private final ObjectMapper objectMapper;

    public IdempotentTemplate(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 以 requestId 幂等方式执行业务动作。
     *
     * @param action 业务动作（运行于独立事务中）；抛 {@link LcBusinessException} 时业务事务回滚，错误响应同样落幂等记录
     */
    public OperationResult execute(String requestId, String fingerprint, OperationType operation,
                                   Supplier<OperationResult.Fresh> action) {
        boolean acquired;
        try {
            acquired = idempotencyRepository.tryInsertProcessing(
                    requestId, fingerprint, operation.name(), java.time.Instant.now());
        } catch (DuplicateKeyException e) {
            acquired = false;
        }
        if (!acquired) {
            return replayOrConflict(requestId, fingerprint, operation);
        }
        try {
            OperationResult.Fresh result = action.get();
            idempotencyRepository.complete(requestId, result.httpStatus(), toJson(result.body()));
            return result;
        } catch (LcBusinessException e) {
            // 确定性业务失败：业务事务已随异常回滚，错误响应落幂等记录后重新抛出（由全局异常处理返回），
            // 同键重放时直接返回首次错误响应。
            ErrorResponse body = new ErrorResponse(e.getErrorCode().name(), e.getMessage());
            idempotencyRepository.complete(requestId, e.getHttpStatus().value(), toJson(body));
            throw e;
        } catch (RuntimeException e) {
            // 非预期异常：释放占位，允许客户端用相同 requestId 重试。
            idempotencyRepository.delete(requestId);
            throw e;
        }
    }

    private OperationResult replayOrConflict(String requestId, String fingerprint, OperationType operation) {
        IdempotentRow existing = idempotencyRepository.find(requestId);
        if (existing == null) {
            // 插入冲突与查询间记录被删除（极端并发），提示客户端重试。
            throw new LcBusinessException(LcErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                    "requestId 处理状态未知，请稍后重试");
        }
        if (!existing.fingerprint().equals(fingerprint) || !existing.operation().equals(operation.name())) {
            throw new LcBusinessException(LcErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                    "相同 requestId 已用于不同业务参数或操作类型");
        }
        for (int i = 0; i < WAIT_ROUNDS; i++) {
            if (existing.httpStatus() != 0) {
                return new OperationResult.Replay(existing.httpStatus(), existing.resultBody());
            }
            try {
                Thread.sleep(WAIT_STEP.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LcBusinessException(LcErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                        "requestId 首次请求仍在处理中");
            }
            existing = idempotencyRepository.find(requestId);
            if (existing == null) {
                throw new LcBusinessException(LcErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                        "requestId 处理状态未知，请稍后重试");
            }
        }
        throw new LcBusinessException(LcErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT,
                "相同 requestId 的首次请求仍在处理中");
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应体序列化失败", e);
        }
    }
}
