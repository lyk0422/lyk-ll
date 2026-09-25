package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 公示异议行。
 */
public record ObjectionRow(long id, long registrationId, String content, ObjectionStatus status, Instant createdAt) {
}
