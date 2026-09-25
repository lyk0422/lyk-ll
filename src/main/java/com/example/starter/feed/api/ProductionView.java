package com.example.starter.feed.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 投产结果视图。
 *
 * @param batchNo               养殖批次号
 * @param formulaCode           投产配方编码
 * @param versionNo             实际投产的冻结版本号
 * @param lastFeedingTime       本次登记的末次含药饲喂时刻
 * @param batchLastFeedingTime  合并后批次最晚末次含药饲喂时刻
 * @param maxWithdrawalDays     合并后批次涉及药物中的最大休药天数（单位：天，整数日界）
 * @param earliestSlaughterDate 最早合法出栏日（UTC）：恰满休药天数当日 24:00 后可出栏，即次日 00:00 UTC 起合法
 */
public record ProductionView(
        String batchNo,
        String formulaCode,
        int versionNo,
        Instant lastFeedingTime,
        Instant batchLastFeedingTime,
        int maxWithdrawalDays,
        LocalDate earliestSlaughterDate) {
}
