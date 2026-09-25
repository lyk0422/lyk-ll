package com.example.starter.wb.repo;

import com.example.starter.wb.domain.IdempotentRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 幂等快照独立事务读写：业务事务回滚时，首次失败结果（409/422 等）仍被保留，
 * 从而同键同参的失败请求也能回放首次结果。
 */
@Component
public class IdempotencyStore {

    private final WeightBalanceRepository repository;

    public IdempotencyStore(WeightBalanceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotentRow> find(String requestId) {
        return repository.findIdempotent(requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(IdempotentRow row) {
        repository.insertIdempotent(row);
    }
}
