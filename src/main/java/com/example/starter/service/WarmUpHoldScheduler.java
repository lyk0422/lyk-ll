package com.example.starter.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保温段超时告警定时扫描：每分钟检查一次，保温恰满4小时未结束的升温票自动升级告警。
 */
@Component
public class WarmUpHoldScheduler {

    private final WarmUpService warmUpService;

    public WarmUpHoldScheduler(WarmUpService warmUpService) {
        this.warmUpService = warmUpService;
    }

    @Scheduled(fixedDelayString = "${cement.warm-up.hold-timeout-scan-delay-ms:60000}")
    public void scan() {
        warmUpService.scanHoldTimeouts();
    }
}
