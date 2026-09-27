package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日前计划发布/重放响应。replayed=true 表示同键同参重放，内容与首次响应一致。
 */
public record PlanPublishResponse(
        Long planId,
        String farmCode,
        LocalDate planDate,
        int version,
        boolean current,
        int slotCount,
        Instant publishedAt,
        boolean replayed) {

    /** 同键同参重放时标记 replayed=true，其余内容与首次响应一致。 */
    public PlanPublishResponse asReplay() {
        return new PlanPublishResponse(planId, farmCode, planDate, version, current,
                slotCount, publishedAt, true);
    }
}
