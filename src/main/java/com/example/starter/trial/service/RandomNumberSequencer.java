package com.example.starter.trial.service;

import com.example.starter.trial.repository.TrialRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 中心随机号发号器。
 *
 * <p>{@link #nextRandomNumber} 在独立事务（REQUIRES_NEW）中完成“初始化计数行 + 行锁递增”
 * 并立即提交。即使调用方业务事务后续因唯一键冲突等原因回滚，已发出的随机号也绝不回收入池，
 * 满足“任何失败或回滚不得重用已发出的随机号”。</p>
 */
@Component
public class RandomNumberSequencer {

    private final TrialRepository repository;
    private final Supplier<Instant> clock;

    public RandomNumberSequencer(TrialRepository repository, Supplier<Instant> clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 发出中心内下一个随机号（从 1 开始，严格递增）。独立事务提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextRandomNumber(String trialId, String siteCode) {
        Instant now = clock.get();
        repository.initSequenceIfAbsent(trialId, siteCode, now);
        return repository.incrementAndGet(trialId, siteCode, now);
    }
}
