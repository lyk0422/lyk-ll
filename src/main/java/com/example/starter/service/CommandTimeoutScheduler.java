package com.example.starter.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 降掺指令超时升级定时扫描：每分钟检查一次，下发恰满30分钟未确认的指令自动升级并强制降掺。
 */
@Component
public class CommandTimeoutScheduler {

    private final CementService cementService;

    public CommandTimeoutScheduler(CementService cementService) {
        this.cementService = cementService;
    }

    @Scheduled(fixedDelayString = "${cement.command-timeout-scan-delay-ms:60000}")
    public void scan() {
        cementService.scanCommandTimeouts();
    }
}
