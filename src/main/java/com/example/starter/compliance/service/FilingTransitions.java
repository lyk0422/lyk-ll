package com.example.starter.compliance.service;

import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.time.ComplianceClock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 备案状态跃迁独立事务封装：
 * 逾期自动暂停必须立即生效，即使外层业务事务随后回滚（如量产上报被拒绝），
 * 因此使用 REQUIRES_NEW 独立提交暂停与有效位释放。
 */
@Component
public class FilingTransitions {

    private final ComplianceRepository repository;
    private final ComplianceClock clock;

    public FilingTransitions(ComplianceRepository repository, ComplianceClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suspendOverdueNewTransaction(FilingRow filing) {
        repository.markSuspended(filing.id(), "OVERDUE", clock.nowMillis());
        repository.releaseProduct(filing.productCode());
    }
}
