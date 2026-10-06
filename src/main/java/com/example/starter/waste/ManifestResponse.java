package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 联单响应，进场登记与幂等重放均返回该结构。
 */
public record ManifestResponse(
        Long manifestId,
        String requestId,
        String plateNo,
        String community,
        String category,
        String facilityCode,
        BigDecimal grossKg,
        BigDecimal tareKg,
        BigDecimal netKg,
        String status,
        LocalDateTime createdAt) {
}
