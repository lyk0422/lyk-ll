package com.example.starter.idempotency;

import com.example.starter.domain.IdempotencyRecord;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.ServiceResult;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.service.BusinessException;
import com.example.starter.time.BusinessClock;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 新增写操作的严格幂等执行器。
 *
 * <p>语义：同键同参重放首次冻结结果；同键异参 409；<b>失败不占键</b>——业务拒绝（4xx）时
 * 占位记录随事务回滚，requestId 可立即复用。成功结果（2xx）与业务、版本变更在同一事务内冻结。
 * 并发同键由主键竞争唯一序列化，落败方等待首事务结局后重放或重新执行。
 */
@Component
public class StrictIdempotencyExecutor {

    private final IdempotencyRepository idempotencyRepository;
    private final JsonSupport jsonSupport;
    private final BusinessClock clock;
    private final TransactionTemplate txTemplate;

    public StrictIdempotencyExecutor(IdempotencyRepository idempotencyRepository,
                                     JsonSupport jsonSupport,
                                     BusinessClock clock,
                                     TransactionTemplate txTemplate) {
        this.idempotencyRepository = idempotencyRepository;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /**
     * 以 requestId 幂等执行写操作。
     *
     * @param requestId   幂等键
     * @param operation   操作类型（指纹命名空间）
     * @param fingerprint 覆盖完整参数（含版本、集合、精确时刻）的结构化指纹
     * @param action      业务动作，返回预期结果（含 4xx 业务拒绝）
     */
    public ServiceResult execute(String requestId, String operation, String fingerprint,
                                 Supplier<ServiceResult> action) {
        IdempotencyRecord existing = idempotencyRepository.find(requestId);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }
        try {
            return txTemplate.execute(status -> {
                idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint, clock.now());
                ServiceResult result = action.get();
                if (result.httpStatus() >= 400) {
                    // 失败不占键：占位与业务变更一并回滚，requestId 不冻结。
                    status.setRollbackOnly();
                } else {
                    idempotencyRepository.finalize(requestId, result.httpStatus(),
                            jsonSupport.write(result.body()));
                }
                return result;
            });
        } catch (DataAccessException ex) {
            // 并发同键：首事务已提交则重放其结果；首事务失败回滚时本请求占位已成功，不会走到这里。
            IdempotencyRecord winner = idempotencyRepository.find(requestId);
            if (winner == null) {
                winner = awaitRecord(requestId);
            }
            return replayOrConflict(winner, fingerprint);
        }
    }

    private ServiceResult replayOrConflict(IdempotencyRecord record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            return ServiceResult.status(409, new ErrorResponse("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 携带了不同业务参数，请求被拒绝且状态不变"));
        }
        return ServiceResult.status(record.getHttpStatus(), jsonSupport.readObject(record.getResponseJson()));
    }

    private IdempotencyRecord awaitRecord(String requestId) {
        for (int i = 0; i < 200; i++) {
            IdempotencyRecord record = idempotencyRepository.find(requestId);
            if (record != null) {
                return record;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(500, "IDEMPOTENCY_WAIT_INTERRUPTED", "等待首次请求结果时被中断");
            }
        }
        throw new BusinessException(500, "IDEMPOTENCY_RECORD_MISSING", "并发幂等记录缺失: " + requestId);
    }
}
