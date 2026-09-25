package com.example.rebate.service;

import com.example.rebate.domain.IdempotentRecord;
import com.example.rebate.repo.IdempotentRecordRepository;
import com.example.rebate.support.FingerprintService;
import com.example.rebate.web.ApiException;
import com.example.rebate.web.ConflictException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * requestId 幂等编排。
 *
 * <p>占坑与结果回写经 {@link IdempotentTxBoundary} 在独立提交事务中完成，业务动作在
 * RebateService 自身事务中执行：业务失败只回滚业务数据，首次结果（含错误码）仍被存档；
 * 同键同参永远返回首次结果，同键异参返回 409 且不改变任何批次状态。</p>
 */
@Service
public class IdempotencyService {

    private final IdempotentRecordRepository records;
    private final IdempotentTxBoundary txBoundary;
    private final FingerprintService fingerprints;

    public IdempotencyService(IdempotentRecordRepository records,
                              IdempotentTxBoundary txBoundary,
                              FingerprintService fingerprints) {
        this.records = records;
        this.txBoundary = txBoundary;
        this.fingerprints = fingerprints;
    }

    /**
     * 以幂等方式执行业务动作。
     *
     * @param opType 操作类型（纳入指纹）
     * @param requestId 幂等键
     * @param payload 全部业务字段（requestId 已单独纳入指纹；路径变量也算业务字段）
     * @param action 首次执行时调用的业务动作（运行于其自身事务）
     */
    public IdempotentOutcome run(String opType, String requestId, Object payload,
                                 Supplier<ServiceResult> action) {
        String fp = fingerprints.fingerprint(opType, requestId, payload);
        boolean acquired = txBoundary.acquire(requestId, opType, fp);
        if (!acquired) {
            IdempotentRecord existing = mustFind(requestId);
            if (!fp.equals(existing.fingerprint())) {
                // 同键异参立即 409，不等待、不触碰任何业务状态。
                throw new ConflictException("requestId=" + requestId + " 已用于不同业务参数");
            }
            // 同键同参并发：等待首次请求完成后回放首次结果。
            IdempotentRecord rec = awaitCompletion(requestId);
            if (rec == null) {
                throw new ConflictException("requestId=" + requestId + " 的首次请求处理超时");
            }
            return IdempotentOutcome.replay(rec.responseStatus(), rec.responseBody());
        }
        try {
            ServiceResult result = action.get();
            txBoundary.complete(requestId, result.status(), fingerprints.toJson(result.body()));
            return IdempotentOutcome.first(result.status(), result.body());
        } catch (ApiException e) {
            // 业务错误同样存档，保证同键重放得到相同错误；业务事务已随异常回滚。
            txBoundary.complete(requestId, e.getStatus(),
                    fingerprints.toJson(errorBody(e.getStatus(), e.getMessage())));
            throw e;
        } catch (RuntimeException e) {
            // 非预期错误也结束占坑记录，避免同键并发调用永久等待。
            txBoundary.complete(requestId, 500, fingerprints.toJson(errorBody(500, "服务内部错误")));
            throw e;
        }
    }

    /**
     * 同键同参并发：轮询等待首次请求回写结果（有界等待 10 秒）。
     */
    private IdempotentRecord awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            IdempotentRecord rec = mustFind(requestId);
            if (rec.responseStatus() != null && rec.responseBody() != null) {
                return rec;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private IdempotentRecord mustFind(String requestId) {
        List<IdempotentRecord> list = records.find(requestId);
        return list.stream().findFirst()
                .orElseThrow(() -> new ConflictException("requestId=" + requestId + " 记录不可读"));
    }

    private Map<String, Object> errorBody(int status, String message) {
        return Map.of("timestamp", OffsetDateTime.now().toString(),
                "status", status, "error", message);
    }
}
