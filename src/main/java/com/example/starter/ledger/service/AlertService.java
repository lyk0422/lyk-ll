package com.example.starter.ledger.service;

import com.example.starter.ledger.po.ChainAlertPO;
import com.example.starter.ledger.repo.ChainAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 链不一致告警服务：即使业务事务最终回滚，告警也必须独立落库。
 */
@Service
public class AlertService {

    private final ChainAlertRepository chainAlertRepository;

    public AlertService(ChainAlertRepository chainAlertRepository) {
        this.chainAlertRepository = chainAlertRepository;
    }

    /** 在独立事务中落库告警，不受外层业务回滚影响。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(ChainAlertPO alert) {
        chainAlertRepository.insert(alert);
    }
}
