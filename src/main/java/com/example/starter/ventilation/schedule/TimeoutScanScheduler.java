package com.example.starter.ventilation.schedule;

import com.example.starter.ventilation.service.VentilationModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 到位反馈超时巡检调度：周期扫描已下发且满 60 秒未到位的指令，
 * 触发重发一次 / 二次超时转故障升级。测试中可用 ventilation.scheduler.enabled=false 关闭。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "ventilation.scheduler", name = "enabled", matchIfMissing = true)
public class TimeoutScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanScheduler.class);

    private final VentilationModeService service;

    public TimeoutScanScheduler(VentilationModeService service) {
        this.service = service;
    }

    /** 固定间隔巡检；间隔由 ventilation.scheduler.fixed-delay-ms 配置。 */
    @Scheduled(fixedDelayString = "${ventilation.scheduler.fixed-delay-ms:1000}")
    public void scan() {
        try {
            int processed = service.scanTimeouts();
            if (processed > 0) {
                log.info("超时巡检处理指令数：{}", processed);
            }
        } catch (Exception e) {
            log.warn("超时巡检失败：{}", e.getMessage());
        }
    }
}
