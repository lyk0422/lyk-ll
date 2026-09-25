package com.example.starter.feed.domain;

import java.time.Instant;

/**
 * 批次的一条含药投产/饲喂义务行；投产时按当时已批准版本固化，后续版本变更不追溯。
 *
 * @param id                  主键
 * @param batchNo             批次号
 * @param formulaCode         配方编码
 * @param versionNo           投产时固化的已批准版本号
 * @param lastFeedingTime     末次饲喂该含药饲料时刻（UTC）
 * @param maxWithdrawalDays   该版本各药物中的最大休药天数
 * @param createdAt           登记时刻（UTC）
 * @param late                是否为出栏后迟到记录
 */
public record BatchMedication(
        Long id,
        String batchNo,
        String formulaCode,
        int versionNo,
        Instant lastFeedingTime,
        int maxWithdrawalDays,
        Instant createdAt,
        boolean late) {
}
