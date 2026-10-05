package com.example.starter.repo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 临时计划快速审批通道结论留痕行。decision 取值：
 * AUTO_APPROVED 快速批准 / PREEMPTED_REGULAR 临时优先常规让路 /
 * YIELDED_TO_REGULAR 临时让路 / REGULAR_YIELDED 常规被让路。
 * 时间均为 UTC；businessDate 为日结核对归属营业日（UTC 日界）。
 */
public record FastTrackDecisionRow(
        Long id,
        long planId,
        LocalDate businessDate,
        String decision,
        String detail,
        Instant decidedAt
) {
}
