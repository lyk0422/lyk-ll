package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 形式审查明细行，可复算。
 */
public record ReviewRecordRow(long id, long registrationId, String item, String result, String detail, Instant createdAt) {
}
