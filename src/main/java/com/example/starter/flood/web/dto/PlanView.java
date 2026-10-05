package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 泄流方案视图；滚动演算追加新版本后，旧版本以 SUPERSEDED 留痕可查。
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
        LocalDateTime createdAt,
        Integer versionNo,
        LocalDateTime supersededAt) {
}
