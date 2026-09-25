package com.example.starter.food.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 逾期未复审自动暂停：每小时扫描 REVIEW_PENDING 备案，超过 90 个自然日窗口者自动暂停。
 * 可通过 food.sweep.enabled=false 关闭（测试中由用例显式控制时钟与扫描）。
 */
@Component
@ConditionalOnProperty(name = "food.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class OverdueSweepScheduler {

    private final FoodService foodService;

    public OverdueSweepScheduler(FoodService foodService) {
        this.foodService = foodService;
    }

    @Scheduled(fixedDelayString = "${food.sweep.fixed-delay-ms:3600000}")
    public void sweep() {
        foodService.sweepOverdue();
    }
}
