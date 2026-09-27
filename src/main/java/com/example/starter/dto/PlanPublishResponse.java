package com.example.starter.dto;

import java.time.Instant;

/**
 * 日前计划发布/重放响应。versionNo 为本风场本日期发布后的当前版本号（首版为 1）。
 * replayed=true 表示同键同参重放，内容与首次响应一致。
 */
public record PlanPublishResponse(
        Long planId,
        String farmCode,
        String planDate,
        int versionNo,
        boolean current,
        Instant publishedAt,
        int segmentCount,
        int turbineCount,
        boolean replayed) {

    /** 同键同参重放时标记 replayed=true，其余内容与首次响应一致。 */
    public PlanPublishResponse asReplay() {
        return new PlanPublishResponse(planId, farmCode, planDate, versionNo, current,
                publishedAt, segmentCount, turbineCount, true);
    }
}
