package com.example.starter.hazmat.web;

import com.example.starter.hazmat.domain.RerouteStatus;
import java.time.LocalDateTime;

/**
 * 改线申请视图。
 */
public record RerouteView(
        long id,
        String requestId,
        long waybillId,
        long newRouteId,
        RerouteStatus status,
        LocalDateTime approvedAt) {
}
