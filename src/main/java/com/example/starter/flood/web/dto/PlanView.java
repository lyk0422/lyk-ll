package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 泄流方案视图。
 */
public record PlanView(
        String planNo,
        String warningNo,
        String routingNo,
        String pondCode,
        List<OpeningItem> openings,
        BigDecimal totalRelease,
        BigDecimal downstreamSafeRelease,
        String status,
        String orderNo,
        String orderStatus,
        boolean replay,
        LocalDateTime createdAt) {
}
