package com.example.starter.cosmetic.api;

import java.time.Instant;

/**
 * 审查明细响应。
 */
public record ReviewRecordDto(String item, String result, String detail, Instant createdAt) {
}
