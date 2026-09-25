package com.example.starter.cutover.dto;

import java.time.LocalDateTime;

/**
 * 割接步骤视图。
 */
public record StepView(
        Integer seqNo,
        String name,
        String type,
        String status,
        LocalDateTime startedAt,
        LocalDateTime reportedAt
) {
}
