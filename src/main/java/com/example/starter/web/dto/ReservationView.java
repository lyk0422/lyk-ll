package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 检测申请视图。体积为微升字符串。
 */
public record ReservationView(
        String requestId,
        String aliquotId,
        String projectCode,
        String volumeUl,
        String status,
        String custodian,
        int version,
        Object result,
        Instant resultAt,
        Instant createdAt,
        Instant updatedAt) {
}
