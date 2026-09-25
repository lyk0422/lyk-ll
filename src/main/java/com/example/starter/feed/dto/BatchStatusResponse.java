package com.example.starter.feed.dto;

import java.time.Instant;
import java.time.LocalDate;


/**
 * 养殖批次休药期跟踪视图，所有字段均可由投产/饲喂/出栏事件复算。
 *
 * @param batchNo                  批次号
 * @param species                  动物种类
 * @param status                   FEEDING / SLAUGHTERED
 * @param lastMedicatedFeedAtUtc   最晚一次含药饲喂时刻（UTC）；无则 null
 * @param withdrawalUntilDate      休药期届满日（UTC 日历日）；该日 24:00 后可出栏
 * @param earliestSlaughterUtc     最早合法出栏时刻（UTC，届满日次日 00:00）；无休药义务为 null
 * @param governingWithdrawalDays  合并口径下决定性最大休药天数
 * @param slaughteredAtUtc         实际出栏时刻（UTC）；未出栏为 null
 */
public record BatchStatusResponse(
        String batchNo,
        String species,
        String status,
        Instant lastMedicatedFeedAtUtc,
        LocalDate withdrawalUntilDate,
        Instant earliestSlaughterUtc,
        Integer governingWithdrawalDays,
        Instant slaughteredAtUtc
) {
}
