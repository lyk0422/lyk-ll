package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 产品主档行。
 */
public record ProductRow(long id, String name, String applicant, String status, Instant createdAt) {
}
