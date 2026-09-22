package com.example.starter.arbitration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台扫描：租约到期、控制器失联、到时指令执行、队列指令超时的一次性检测与安全降级。
 */
@Component
public class ReaperTask {

    private static final Logger log = LoggerFactory.getLogger(ReaperTask.class);

    private final SafetyReaper safetyReaper;

    public ReaperTask(SafetyReaper safetyReaper) {
        this.safetyReaper = safetyReaper;
    }

    @Scheduled(fixedDelayString = "${arbitration.reaper.interval-ms:200}")
    public void scan() {
        try {
            safetyReaper.reapOnce();
        } catch (RuntimeException ex) {
            // 单轮失败不影响后续调度
            log.warn("安全回收扫描轮次失败: {}", ex.getMessage());
        }
    }
}
