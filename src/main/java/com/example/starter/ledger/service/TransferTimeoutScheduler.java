package com.example.starter.ledger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调拨超时扫描器：固定延迟检查在途超过 24 小时（UTC）的调拨单并转异常。
 * 测试中可直接调用 {@link LedgerService#markTimedOutTransfers()} 精确控制时钟。
 */
@Component
public class TransferTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransferTimeoutScheduler.class);

    private final LedgerService ledgerService;

    public TransferTimeoutScheduler(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** 每 15 分钟扫描一次，启动 15 分钟后首次执行；单实例内存服务无需分布式锁。 */
    @Scheduled(fixedDelayString = "${battery.ledger.timeout-scan-delay-ms:900000}",
            initialDelayString = "${battery.ledger.timeout-scan-initial-delay-ms:900000}")
    public void scan() {
        int marked = ledgerService.markTimedOutTransfers();
        if (marked > 0) {
            log.warn("调拨在途超时，自动转异常 {} 单", marked);
        }
    }
}
