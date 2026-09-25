package com.example.starter.cutover.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接申请视图。
 */
public record ApplicationResponse(
        Long id,
        String requestId,
        String deviceCode,
        String title,
        String priority,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        String status,
        String rejectReason,
        List<StepView> steps,
        /** true 表示本次为同键同参重放，返回首次结果。 */
        boolean replay
) {

    /**
     * 步骤视图。
     */
    public record StepView(
            Integer seq,
            String name,
            String stepType,
            String status,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
    }
}
