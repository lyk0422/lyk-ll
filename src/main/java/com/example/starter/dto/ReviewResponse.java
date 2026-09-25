package com.example.starter.dto;

import java.time.Instant;

import com.example.starter.domain.FreezeReview;

/**
 * 复核解冻响应。unfrozen 表示本次调用实际执行了解冻。
 */
public record ReviewResponse(
        long id,
        String requestId,
        long mineId,
        String reviewer,
        Instant reviewedAt,
        boolean unfrozen) {

    /**
     * 由领域对象构造响应。
     */
    public static ReviewResponse from(FreezeReview r, boolean unfrozen) {
        return new ReviewResponse(r.id(), r.requestId(), r.mineId(), r.reviewer(),
                r.reviewedAt(), unfrozen);
    }
}
