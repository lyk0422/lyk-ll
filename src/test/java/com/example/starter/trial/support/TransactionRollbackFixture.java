package com.example.starter.trial.support;

import com.example.starter.trial.domain.RandomNumberLedger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试夹具：外层事务发放随机号后主动回滚，
 * 用于验证随机号在独立事务中已提交、外层回滚不会导致重号。
 */
@Component
public class TransactionRollbackFixture {

    private final RandomNumberLedger randomNumberLedger;

    public TransactionRollbackFixture(RandomNumberLedger randomNumberLedger) {
        this.randomNumberLedger = randomNumberLedger;
    }

    @Transactional
    public void issueThenRollback(String trialCode, String centerCode) {
        randomNumberLedger.issue(trialCode, centerCode);
        throw new IllegalStateException("模拟入组事务在外层失败回滚");
    }
}
