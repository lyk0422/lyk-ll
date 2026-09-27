package com.example.rebate.service;

import com.example.rebate.repo.ChargebackRequestRepository;
import com.example.rebate.support.FingerprintService;
import com.example.rebate.web.ConflictException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 冲回写操作的 requestId 幂等编排（同事务语义）。
 *
 * <p>与旧入口的独立事务占坑不同，冲回写操作要求“业务、版本与幂等结果同事务”：占坑行在业务
 * 事务内插入，与业务变更一起提交；业务失败时整事务回滚，占坑行随之消失（失败不占键，可用同键
 * 或换键重试）。并发同键在主键上阻塞：先提交者完成占坑并回写结果，后来者读到的必是已提交的
 * 首次记录——指纹一致则回放首次冻结结果，不一致立即 409。</p>
 */
@Service
public class ChargebackIdempotency {

    private final ChargebackRequestRepository records;
    private final FingerprintService fingerprints;

    public ChargebackIdempotency(ChargebackRequestRepository records,
                                 FingerprintService fingerprints) {
        this.records = records;
        this.fingerprints = fingerprints;
    }

    /**
     * 在同一事务内占坑、执行业务并回写结果。
     *
     * @param opType 操作类型（纳入结构化指纹）
     * @param requestId 幂等键
     * @param payload 全部业务字段（含路径变量、expectedVersion、集合与精确时刻）
     * @param action 首次执行的业务动作，与占坑同事务
     */
    @Transactional
    public IdempotentOutcome run(String opType, String requestId, Object payload,
                                 java.util.function.Supplier<ServiceResult> action) {
        String fp = fingerprints.fingerprint(opType, requestId, payload);
        boolean acquired = records.tryAcquire(requestId, opType, fp,
                java.time.Instant.now());
        if (!acquired) {
            Map<String, Object> existing = records.find(requestId);
            if (existing == null) {
                // 理论上不可达：插入失败必然存在已提交行。
                throw new ConflictException("requestId=" + requestId + " 并发处理异常");
            }
            if (!fp.equals(String.valueOf(existing.get("FINGERPRINT")))
                    && !fp.equals(String.valueOf(existing.get("fingerprint")))) {
                throw new ConflictException("requestId=" + requestId + " 已用于不同业务参数");
            }
            Object status = existing.get("RESPONSE_STATUS");
            if (status == null) {
                status = existing.get("response_status");
            }
            Object body = existing.get("RESPONSE_BODY");
            if (body == null) {
                body = existing.get("response_body");
            }
            return IdempotentOutcome.replay(((Number) status).intValue(), String.valueOf(body));
        }
        ServiceResult result = action.get();
        records.complete(requestId, result.status(), fingerprints.toJson(result.body()));
        return IdempotentOutcome.first(result.status(), result.body());
    }
}
