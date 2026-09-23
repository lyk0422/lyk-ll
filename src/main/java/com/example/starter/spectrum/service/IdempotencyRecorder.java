package com.example.starter.spectrum.service;

import com.example.starter.spectrum.repo.LeaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等记录写入器。必须在持有协调锁的事务内调用：
 * 首次请求结果与业务写入在同一事务提交，保证并发下同键请求
 * 在锁后读到已提交的首次结果（成功或失败）。
 */
@Component
public class IdempotencyRecorder {

    private final LeaseRepository repository;

    public IdempotencyRecorder(LeaseRepository repository) {
        this.repository = repository;
    }

    /** 在当前协调事务内持久化首次请求结果。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String requestId, String fingerprint, int httpStatus,
                       String errorCode, String errorBody, Long resultLeaseId, long now) {
        repository.insertIdempotency(requestId, fingerprint, httpStatus,
                errorCode, errorBody, resultLeaseId, now);
    }
}
