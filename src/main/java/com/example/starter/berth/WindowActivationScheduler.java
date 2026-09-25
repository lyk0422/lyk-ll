package com.example.starter.berth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到点自动生效：周期性将到达起始时刻的已批准窗口转为已生效。
 * 可通过 berth.activation.scheduler-enabled=false 关闭（测试使用可控时钟手动触发）。
 */
@Component
@ConditionalOnProperty(name = "berth.activation.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class WindowActivationScheduler {

    private static final Logger log = LoggerFactory.getLogger(WindowActivationScheduler.class);

    private final BerthWindowService service;

    public WindowActivationScheduler(BerthWindowService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "1000", initialDelayString = "1000")
    public void activateDue() {
        int activated = service.activateDueWindows();
        if (activated > 0) {
            log.info("activated {} berth window(s)", activated);
        }
    }
}
