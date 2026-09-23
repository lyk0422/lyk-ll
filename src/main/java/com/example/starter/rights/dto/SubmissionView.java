package com.example.starter.rights.dto;

import java.time.Instant;
import java.util.List;

/**
 * 排期提交回执。
 */
public record SubmissionView(
        String submissionId,
        Instant createdAt,
        List<ScheduleItemView> items) {
}
