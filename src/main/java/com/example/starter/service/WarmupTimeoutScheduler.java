package com.example.starter.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 升温票超时定时扫描：每分钟检查一次，升温段恰满最长用时未确认自动转保温段，
 * 保温段恰满 4 小时升级告警。
 */
@Component
public class WarmupTimeoutScheduler {

    private final WarmupService warmupService;

    public WarmupTimeoutScheduler(WarmupService warmupService) {
        this.warmupService = warmupService;
    }

    @Scheduled(fixedDelayString = "${cement.warmup-timeout-scan-delay-ms:60000}")
    public void scan() {
        warmupService.scanWarmupTimeouts();
    }
}
