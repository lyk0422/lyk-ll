package com.example.starter.support;

import com.example.starter.service.ReplenishService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每分钟扫描押运超时与挂账逾期：恰满 4 小时未完成双人打卡转异常冻结；
 * OPEN 挂账超过截止日（UTC）升级并冻结责任人。时钟走 UTC，与业务判定一致。
 */
@Configuration
@EnableScheduling
public class TimeoutScheduler {

    private final ReplenishService service;

    public TimeoutScheduler(ReplenishService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.sweep.fixed-delay-ms:60000}")
    public void sweep() {
        service.sweepTimeouts();
    }
}
