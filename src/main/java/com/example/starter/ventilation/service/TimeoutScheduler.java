package com.example.starter.ventilation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时扫描设备到位超时：默认开启，测试可通过
 * app.ventilation.scheduler-enabled=false 关闭以保证时钟可控、断言确定。
 */
@Component
@ConditionalOnProperty(name = "app.ventilation.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class TimeoutScheduler {

    private final VentilationService service;

    public TimeoutScheduler(VentilationService service) {
        this.service = service;
    }

    /** 每 5 秒扫描一次；60 秒超时判定以指令下发时间戳为准，恰满即超时。 */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void sweep() {
        // 重发、设备故障与告警升级均已按序写入事件流。
        service.sweepTimeouts();
    }
}
