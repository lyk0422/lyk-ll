package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泄流方案版本视图：旧版本留痕可查。
 */
public record PlanVersionView(
        String planNo,
        Integer version,
        String status,
        BigDecimal totalRelease,
        String orderNo,
        LocalDateTime createdAt,
        LocalDateTime supersededAt,
        LocalDateTime archivedAt) {
}
