package com.example.starter.transfer;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时扫描定时任务：恰满 48 小时未核销的在途单自动转异常。
 * 可通过 transfer.scanner.enabled=false 关闭（测试使用可控时钟手动触发）。
 */
@Component
@ConditionalOnProperty(prefix = "transfer.scanner", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransferTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(TransferTimeoutScanner.class);

    private final TransferService service;

    public TransferTimeoutScanner(TransferService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${transfer.scanner.interval-ms:60000}", initialDelayString = "${transfer.scanner.interval-ms:60000}")
    public void scan() {
        List<Long> expired = service.scanExpired();
        if (!expired.isEmpty()) {
            log.info("超时转异常单据: {}", expired);
        }
    }
}
