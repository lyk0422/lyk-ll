package com.example.rebate.service;

import com.example.rebate.domain.IdempotentRecord;
import com.example.rebate.repo.IdempotentRecordRepository;
import com.example.rebate.support.FingerprintService;
import com.example.rebate.web.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 冲回流程专用 requestId 幂等编排：占坑、业务变更（含版本递增）与结果回写在
 * <b>同一个业务事务</b>内提交（调用方方法须带 {@code @Transactional}）。
 *
 * <ul>
 *   <li>同键同参：先提交者执行业务并冻结首次结果；后到者读取冻结结果原样重放，
 *       即使后续余额/版本已变化也不重新计算；</li>
 *   <li>同键异参：立即 409，不触碰任何业务状态；</li>
 *   <li>首次业务失败：异常使整个事务回滚，占坑行一并撤销——失败不占键，同键稍后可重新执行；</li>
 *   <li>并发同键：后来事务的占坑 INSERT 在唯一键行锁上等待，先者回滚则后来者自动成为首次执行者。</li>
 * </ul>
 */
@Service
public class ReversalIdempotencyService {

    private final IdempotentRecordRepository records;
    private final FingerprintService fingerprints;
    private final ObjectMapper objectMapper;

    public ReversalIdempotencyService(IdempotentRecordRepository records,
                                      FingerprintService fingerprints,
                                      ObjectMapper objectMapper) {
        this.records = records;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
    }

    /**
     * 在当前业务事务内以幂等方式执行动作，成功结果统一按 HTTP 200 冻结存档。
     *
     * @param opType 操作类型（纳入结构化指纹）
     * @param requestId 幂等键
     * @param payload 全部业务字段（含 expectedVersion 与精确时刻 occurredAt；路径变量也算）
     * @param resultType 重放时首次冻结结果的还原类型
     * @param action 首次执行的业务动作，与占坑/结果同事务
     */
    public <T> T run(String opType, String requestId, Object payload, Class<T> resultType,
                     Supplier<T> action) {
        String fp = fingerprints.fingerprint(opType, requestId, payload);
        // 占坑使用默认 REQUIRED 传播：加入调用方业务事务，失败随业务一起回滚（失败不占键）。
        boolean acquired = records.tryAcquire(requestId, opType, fp, java.time.Instant.now());
        if (!acquired) {
            IdempotentRecord existing = mustFind(requestId);
            if (!fp.equals(existing.fingerprint())) {
                throw new ConflictException("requestId=" + requestId + " 已用于不同业务参数");
            }
            if (existing.responseStatus() == null || existing.responseBody() == null) {
                // 仅可能与旧流程“先提交占坑”的在途记录撞键；不等待，按冲突处理。
                throw new ConflictException("requestId=" + requestId + " 的首次请求仍在处理中");
            }
            try {
                // 重放首次冻结结果：不随后续余额/版本变化重新计算。
                return objectMapper.readValue(existing.responseBody(), resultType);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("首次冻结结果还原失败", e);
            }
        }
        // 业务异常直接向外抛：业务事务回滚，占坑行同事务撤销，键被释放（失败不占键）。
        T result = action.get();
        records.complete(requestId, 200, fingerprints.toJson(result), java.time.Instant.now());
        return result;
    }

    private IdempotentRecord mustFind(String requestId) {
        List<IdempotentRecord> list = records.find(requestId);
        return list.stream().findFirst()
                .orElseThrow(() -> new ConflictException("requestId=" + requestId + " 记录不可读"));
    }
}
