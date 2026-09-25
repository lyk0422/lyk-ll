package com.example.rebate.service;

import com.example.rebate.repo.IdempotentRecordRepository;
import com.example.rebate.support.TimeService;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等记录占坑/回写的独立事务边界。
 *
 * <p>必须经独立 Bean 调用，避免同类自调用导致 {@link Propagation#REQUIRES_NEW} 代理失效：
 * 占坑行立即提交以便并发请求感知；业务事务回滚不影响已存档的首次结果。</p>
 */
@Component
public class IdempotentTxBoundary {

    private final IdempotentRecordRepository records;
    private final TimeService timeService;

    public IdempotentTxBoundary(IdempotentRecordRepository records, TimeService timeService) {
        this.records = records;
        this.timeService = timeService;
    }

    /**
     * 独立事务占坑，立即提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String requestId, String opType, String fingerprint) {
        return records.tryAcquire(requestId, opType, fingerprint, timeService.now());
    }

    /**
     * 独立事务回写首次结果，立即提交；不与业务事务共生死。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String requestId, int status, String bodyJson) {
        Instant now = timeService.now();
        records.complete(requestId, status, bodyJson, now);
    }
}
