package com.example.starter.berth.config;

import com.example.starter.berth.service.BerthWindowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：周期性将到达起始时刻的已批准窗口转为已生效。
 * 可通过 app.scheduling.enabled=false 关闭（测试中用显式调用代替）。
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WindowActivationJob {

    private final BerthWindowService service;

    public WindowActivationJob(BerthWindowService service) {
        this.service = service;
    }

    /**
     * 每 2 秒执行一次到期窗口生效。
     */
    @Scheduled(fixedDelayString = "2000", initialDelayString = "2000")
    public void activateDueWindows() {
        service.activateDueWindows();
    }
}
