package com.example.starter.hazmat.config;

import com.example.starter.hazmat.service.TimeoutScanService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时超时复算：默认每分钟对在途运单复算一次；测试通过 hazmat.scan.enabled=false 关闭，
 * 改用可控时钟手动触发。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hazmat.scan.enabled", havingValue = "true", matchIfMissing = true)
public class ScanSchedulingConfig {

    private final TimeoutScanService scanService;

    public ScanSchedulingConfig(TimeoutScanService scanService) {
        this.scanService = scanService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void scanTimeouts() {
        scanService.scanOnce();
    }
}
