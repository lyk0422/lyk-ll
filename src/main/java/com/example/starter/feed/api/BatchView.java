package com.example.starter.feed.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 批次休药期跟踪视图（可据此复算休药期、禁忌命中与版本轨迹）。
 *
 * @param batchNo               批次号
 * @param species               动物种类
 * @param status                ACTIVE / SLAUGHTERED
 * @param createdAt             建档时刻（UTC）
 * @param slaughteredAt         出栏时刻（UTC）；未出栏为空
 * @param latestFeedingTime     最晚末次含药饲喂时刻（UTC）；无含药义务为空
 * @param maxWithdrawalDays     合并后最大休药天数；无含药义务为 0
 * @param earliestSlaughterDate 最早合法出栏日（UTC）；无含药义务为空
 * @param medications           含药义务行轨迹（含投产版本号；迟到行单独标记）
 * @param alerts                合规告警列表
 */
public record BatchView(
        String batchNo,
        String species,
        String status,
        Instant createdAt,
        Instant slaughteredAt,
        Instant latestFeedingTime,
        int maxWithdrawalDays,
        LocalDate earliestSlaughterDate,
        List<Medication> medications,
        List<Alert> alerts) {

    /** 含药义务行。 */
    public record Medication(String formulaCode, int versionNo, Instant lastFeedingTime,
                             int maxWithdrawalDays, boolean late) {
    }

    /** 合规告警。 */
    public record Alert(String alertType, String detail, Instant createdAt) {
    }
}
