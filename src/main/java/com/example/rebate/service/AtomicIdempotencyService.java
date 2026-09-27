package com.example.rebate.service;

import com.example.rebate.domain.IdempotentRecord;
import com.example.rebate.repo.IdempotentRecordRepository;
import com.example.rebate.support.FingerprintService;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 新增写操作的同事务幂等编排。
 *
 * <p>与旧入口“先独立事务占坑、业务另开事务”不同，本编排把 <b>幂等占坑行、业务变更
 * （含乐观版本加 1）、首次结果回写</b> 放在同一个全新数据库事务内：</p>
 * <ul>
 *   <li>成功：业务、版本与幂等结果原子提交；重放永远返回首次冻结结果，不随后续余额变化重算；</li>
 *   <li>失败（含 4xx 业务拒绝）：整事务回滚，requestId 不被占用（“失败不占键”），同键可重试；</li>
 *   <li>同键异参：409，不触碰业务状态；</li>
 *   <li>同键同参并发：唯一约束保证只有一个事务进入业务，落败者等待首次提交后回放；
 *       若首次事务回滚释放键，等待者的插入自然接手成功。</li>
 * </ul>
 */
@Service
public class AtomicIdempotencyService {

    /** 同键并发插入落败的内部信号：本事务回滚后转入等待回放。 */
    private static final class KeyRaceSignal extends RuntimeException {
        private KeyRaceSignal() {
            super(null, null, false, false);
        }
    }

    private final IdempotentRecordRepository records;
    private final FingerprintService fingerprints;
    private final TimeService timeService;
    private final TransactionTemplate transactionTemplate;

    public AtomicIdempotencyService(IdempotentRecordRepository records,
                                    FingerprintService fingerprints,
                                    TimeService timeService,
                                    PlatformTransactionManager txManager) {
        this.records = records;
        this.fingerprints = fingerprints;
        this.timeService = timeService;
        // 全新事务：占坑失败时干净回滚，不污染任何外层事务。
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 以“业务与幂等同事务”的方式执行动作。
     *
     * @param opType 操作类型（纳入指纹）
     * @param requestId 幂等键
     * @param payload 全部业务字段（含 expectedVersion、集合与精确时刻，路径变量也算）
     * @param action 首次执行的业务动作，运行于幂等同事务内
     */
    public IdempotentOutcome run(String opType, String requestId, Object payload,
                                 Supplier<ServiceResult> action) {
        String fingerprint = fingerprints.fingerprint(opType, requestId, payload);
        // 最多两轮：首轮抢占；若等待期间对方事务失败回滚释放键，第二轮自行接手。
        for (int attempt = 0; attempt < 2; attempt++) {
            IdempotentOutcome outcome;
            try {
                ServiceResult result = transactionTemplate.execute(status -> {
                    // 唯一约束是并发终裁：未提交的同行也会令本插入等待/落败。
                    if (!records.tryAcquire(requestId, opType, fingerprint, timeService.now())) {
                        throw new KeyRaceSignal();
                    }
                    ServiceResult r = action.get();
                    // 业务结果与业务数据同一事务回写、一同提交。
                    records.complete(requestId, r.status(),
                            fingerprints.toJson(r.body()), timeService.now());
                    return r;
                });
                outcome = IdempotentOutcome.first(result.status(), result.body());
            } catch (KeyRaceSignal signal) {
                outcome = null;
            }
            if (outcome != null) {
                return outcome;
            }

            // 抢占落败：等待首次事务提交。对方回滚则行消失（返回 null），可接手重试。
            IdempotentRecord done = awaitCompletion(requestId);
            if (done == null) {
                continue;
            }
            if (!fingerprint.equals(done.fingerprint())) {
                throw new ConflictException("requestId=" + requestId + " 已用于不同业务参数");
            }
            return IdempotentOutcome.replay(done.responseStatus(), done.responseBody());
        }
        throw new ConflictException("requestId=" + requestId + " 并发竞争重试耗尽，请稍后重试");
    }

    /**
     * 有界等待首次事务完成；占坑行随对方事务回滚而消失时返回 {@code null}（键已释放）。
     */
    private IdempotentRecord awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            IdempotentRecord rec = records.find(requestId).stream().findFirst().orElse(null);
            if (rec == null) {
                return null;
            }
            if (rec.responseStatus() != null && rec.responseBody() != null) {
                return rec;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("等待 requestId=" + requestId + " 首次结果时被中断");
            }
        }
        throw new ConflictException("requestId=" + requestId + " 的首次请求处理超时");
    }
}
