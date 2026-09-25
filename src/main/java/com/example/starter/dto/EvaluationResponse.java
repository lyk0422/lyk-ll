package com.example.starter.dto;

import java.time.Instant;

import com.example.starter.domain.Evaluation;

/**
 * 效果评估响应。sealed 恒为 true（封档为终态）。
 */
public record EvaluationResponse(
        long id,
        String requestId,
        long permitId,
        String conclusion,
        Instant sealedAt,
        boolean sealed) {

    /**
     * 由领域对象构造响应。
     */
    public static EvaluationResponse from(Evaluation e) {
        return new EvaluationResponse(e.id(), e.requestId(), e.permitId(), e.conclusion(),
                e.sealedAt(), true);
    }
}
