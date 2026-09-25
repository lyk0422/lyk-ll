package com.example.starter.forestry.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.starter.forestry.service.ForestryService;

/**
 * 每日（UTC 零点后）扫描逾期采伐证：自动作废并当日释放限额。
 * 扫描幂等，重复执行不会重复释放。
 */
@Component
public class ExpiredLicenseScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredLicenseScheduler.class);

    private final ForestryService forestryService;

    public ExpiredLicenseScheduler(ForestryService forestryService) {
        this.forestryService = forestryService;
    }

    /**
     * 每天 UTC 00:05 执行一次逾期扫描。
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void scanExpiredLicenses() {
        int count = forestryService.processExpiredLicenses();
        if (count > 0) {
            log.info("逾期采伐证自动作废 {} 张，限额已当日释放", count);
        }
    }
}
