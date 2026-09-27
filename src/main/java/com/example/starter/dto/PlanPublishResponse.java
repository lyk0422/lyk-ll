package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日前计划版本发布结果。replayed=true 表示同键同参重放，内容与首次冻结结果一致。
 */
public record PlanPublishResponse(
        Long versionId,
        String farmCode,
        LocalDate planDate,
        int versionNo,
        boolean current,
        Instant publishedAt,
        int segmentCount,
        int turbineCount,
        String requestId,
        boolean replayed) {

    public PlanPublishResponse asReplay() {
        return new PlanPublishResponse(versionId, farmCode, planDate, versionNo, current,
                publishedAt, segmentCount, turbineCount, requestId, true);
    }
}
