package com.example.starter.feed.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 含药饲喂记录上报请求（用于模拟饲喂记录延迟到达等场景）。
 *
 * @param batchNo      养殖批次号
 * @param formulaCode  含药配方编码
 * @param versionNo    配方版本号；为空时取最新已批准版本
 * @param feedingTime  实际饲喂时刻（UTC，ISO-8601）
 */
public record FeedingRecordRequest(
        @NotBlank String batchNo,
        @NotBlank String formulaCode,
        Integer versionNo,
        Instant feedingTime) {
}
