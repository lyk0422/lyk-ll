package com.example.starter.feed.dto;

import java.time.Instant;

/**
 * 投产响应。
 *
 * @param formulaCode             配方编码
 * @param version                 投产版本（已批准冻结）
 * @param batchNo                 养殖批次号
 * @param producedAtUtc           投产时刻（UTC）
 * @param lastMedicatedFeedAtUtc  投产后批次合并口径的最晚含药饲喂时刻（UTC）
 * @param withdrawalUntilDate     投产后批次合并休药期届满日（UTC 日历日）
 * @param earliestSlaughterUtc    投产后批次最早合法出栏时刻（UTC）
 * @param governingWithdrawalDays 合并口径决定性最大休药天数
 */
public record ProductionResponse(
        String formulaCode,
        int version,
        String batchNo,
        Instant producedAtUtc,
        Instant lastMedicatedFeedAtUtc,
        java.time.LocalDate withdrawalUntilDate,
        Instant earliestSlaughterUtc,
        Integer governingWithdrawalDays
) {
}
