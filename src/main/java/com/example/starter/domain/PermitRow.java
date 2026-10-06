package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 作业许可行；deadline 为签发时既定时限，计划重排不修改。
 */
public record PermitRow(long id, String permitNo, long segmentId, long planId, WorkType workType,
                        PermitStatus status, LocalDateTime deadline,
                        LocalDateTime createdAt, LocalDateTime completedAt) {
}
