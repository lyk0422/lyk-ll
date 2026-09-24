package com.example.starter.enrollment;

import java.time.Instant;

/**
 * 入组响应。replayed=true 表示同键同参幂等重放，返回首次分配结果。
 */
public record EnrollmentResponse(
        String requestId,
        String screeningNo,
        String centerCode,
        long randomNo,
        String armCode,
        String status,
        Instant enrolledAt,
        boolean replayed) {
}
