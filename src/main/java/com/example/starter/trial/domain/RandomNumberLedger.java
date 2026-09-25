package com.example.starter.trial.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随机号发放台账：中心内递增。更新在独立事务中立即提交，
 * 因此外层入组事务即使失败或回滚，已发出的随机号也不会被再次发放（允许空号）。
 */
@Repository
public class RandomNumberLedger {

    private final JdbcTemplate jdbcTemplate;

    public RandomNumberLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 原子领取下一个随机号：UPDATE 持有的行锁在本独立事务提交后才释放，
     * 并发发放因此串行化，不可能发出相同号码。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long issue(String trialCode, String centerCode) {
        int updated = jdbcTemplate.update(
                "UPDATE random_number_ledger SET next_random_no = next_random_no + 1 "
                        + "WHERE trial_code = ? AND center_code = ?",
                trialCode, centerCode);
        if (updated == 0) {
            throw new BusinessRuleException("中心不存在或未初始化: " + centerCode);
        }
        Long next = jdbcTemplate.queryForObject(
                "SELECT next_random_no FROM random_number_ledger WHERE trial_code = ? AND center_code = ?",
                Long.class, trialCode, centerCode);
        return next - 1L;
    }
}
