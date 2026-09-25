package com.example.starter.support;

import com.example.starter.repo.IdempotencyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 幂等记录的独立事务操作（REQUIRES_NEW）：占位、落结果、释放占位必须脱离业务事务提交，
 * 因此单独成 Bean 以保证经过 Spring 事务代理。
 */
@Component
public class IdempotencyTx {

    private final IdempotencyRepository repository;
    private final Clock clock;

    public IdempotencyTx(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertProcessing(String requestId, String requestKind, String fingerprint) {
        repository.insertProcessing(requestId, requestKind, fingerprint, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(String requestId, int responseStatus, String responseBody) {
        repository.markDone(requestId, responseStatus, responseBody, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String requestId) {
        repository.markFailed(requestId, clock.instant());
    }

    /**
     * 原子接管 FAILED 占位（调用方已确认指纹一致），返回是否接管成功。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean takeOverFailed(String requestId) {
        return repository.takeOverFailed(requestId, clock.instant()) == 1;
    }
}
