package com.example.starter.dispatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时升级定时扫描：周期性检查超过执行时限未收齐回执的令，自动升级并通知上级。
 * 测试环境可通过 dispatch.sweep.enabled=false 关闭，改为手动触发以协调竞态。
 */
@Component
@ConditionalOnProperty(name = "dispatch.sweep.enabled", matchIfMissing = true)
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    private final DispatchService dispatchService;

    public TimeoutSweeper(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${dispatch.sweep.fixed-delay:5000}",
            initialDelayString = "${dispatch.sweep.initial-delay:2000}")
    public void sweep() {
        int count = dispatchService.sweepTimeouts();
        if (count > 0) {
            log.warn("超时升级完成，本次升级调度令 {} 张", count);
        }
    }
}
