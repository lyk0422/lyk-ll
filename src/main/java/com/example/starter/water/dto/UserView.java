package com.example.starter.water.dto;

import java.math.BigDecimal;

/**
 * 取水户视图；restricted=true 表示当前处于生态流量预警“限取”状态。
 */
public record UserView(
        Long id,
        String name,
        String riverSection,
        BigDecimal annualQuota,
        boolean restricted) {
}
