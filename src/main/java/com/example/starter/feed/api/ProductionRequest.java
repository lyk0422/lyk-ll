package com.example.starter.feed.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 含药配方投产请求：将一个已批准冻结版本投产到指定养殖批次，并登记末次饲喂含药饲料时刻。
 *
 * @param requestId       幂等键
 * @param batchNo         养殖批次号
 * @param formulaCode     配方编码
 * @param versionNo       指定投产版本；为空时取该配方最新已批准版本
 * @param lastFeedingTime 末次饲喂该含药饲料的时刻（UTC，ISO-8601），休药期从该时刻所在 UTC 日起算
 */
public record ProductionRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String formulaCode,
        Integer versionNo,
        Instant lastFeedingTime) {
}
