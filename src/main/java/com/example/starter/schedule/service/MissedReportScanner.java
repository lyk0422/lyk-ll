package com.example.starter.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 漏报定时扫描器；测试中可通过 schedule.missed-report.enabled=false 关闭以精确控制时序。
 */
@Component
@ConditionalOnProperty(name = "schedule.missed-report.enabled", havingValue = "true",
        matchIfMissing = true)
public class MissedReportScanner {

    private static final Logger log = LoggerFactory.getLogger(MissedReportScanner.class);

    private final ReportService reportService;

    public MissedReportScanner(ReportService reportService) {
        this.reportService = reportService;
    }

    @Scheduled(fixedDelayString = "${schedule.missed-report.scan-interval-ms:5000}",
            initialDelayString = "${schedule.missed-report.scan-interval-ms:5000}")
    public void scan() {
        try {
            int missed = reportService.scanMissed();
            if (missed > 0) {
                log.info("漏报扫描完成，新标记 {} 条", missed);
            }
        } catch (Exception e) {
            log.warn("漏报扫描异常", e);
        }
    }
}
