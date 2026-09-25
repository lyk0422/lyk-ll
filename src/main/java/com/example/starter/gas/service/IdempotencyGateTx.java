package com.example.starter.gas.service;

import com.example.starter.gas.repo.GasRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等门控的事务边界 Bean：每个方法都是独立事务（REQUIRES_NEW），
 * 供 {@link IdempotencyGate} 跨 Bean 调用以确保 Spring 事务代理生效。
 */
@Component
public class IdempotencyGateTx {

    private final GasRepository repo;

    public IdempotencyGateTx(GasRepository repo) {
        this.repo = repo;
    }

    /** 插入占位；唯一键冲突时仅该独立事务回滚，返回 false。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsertPending(String requestId, String requestType, String fingerprint) {
        try {
            repo.insertPendingIdempotent(requestId, requestType, fingerprint);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 独立事务读取，调用方每次读取都能看到最新已提交状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GasRepository.IdempotentRow readFresh(String requestId) {
        return repo.findIdempotent(requestId);
    }

    /** 首发业务事务回滚后，在独立事务清理占位。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String requestId) {
        repo.deleteIdempotent(requestId);
    }
}
