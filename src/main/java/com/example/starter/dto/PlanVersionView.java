package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日前计划版本摘要（版本列表/当前版本查询用）。
 */
public record PlanVersionView(
        Long planId,
        String farmCode,
        LocalDate planDate,
        int version,
        boolean current,
        Instant publishedAt) {
}
