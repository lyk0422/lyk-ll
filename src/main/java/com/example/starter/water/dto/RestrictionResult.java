package com.example.starter.water.dto;

/**
 * 限取触发结果；affectedUsers 为受影响河段进入“限取”状态的取水户数量。
 */
public record RestrictionResult(
        Long id,
        String riverSection,
        String effectiveAt,
        String reason,
        int affectedUsers) {
}
