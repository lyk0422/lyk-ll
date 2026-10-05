package com.example.starter.support;

import com.example.starter.repo.RehearsalRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预演留档的独立事务操作（REQUIRES_NEW）：能耗超预算拒绝下发时业务事务回滚，
 * 但预演结论仍须落库可查，因此单独成 Bean 以保证经过 Spring 事务代理。
 */
@Component
public class RehearsalTx {

    private final RehearsalRepository rehearsalRepository;

    public RehearsalTx(RehearsalRepository rehearsalRepository) {
        this.rehearsalRepository = rehearsalRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertInNewTransaction(RehearsalRepository.Rehearsal rehearsal) {
        rehearsalRepository.insert(rehearsal);
    }
}
