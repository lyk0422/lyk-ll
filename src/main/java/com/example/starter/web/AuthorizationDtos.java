package com.example.starter.web;

import com.example.starter.domain.AuthorizationStatus;
import com.example.starter.domain.ZoneAuthorization;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 授权申请请求体。时间为 UTC ISO-8601（如 2026-09-25T10:00:00Z）。
 */
public record AuthorizationDtos() {

    public record Request(String requestId, String craneId, Instant startTime, Instant endTime,
                          BigDecimal loadTonnes) {
    }

    public record Response(Long id, String requestId, String craneId, String zoneId,
                           Instant startTimeUtc, Instant endTimeUtc, BigDecimal loadTonnes,
                           AuthorizationStatus status, Instant createdAtUtc, Instant releasedAtUtc,
                           boolean replayed) {

        public static Response of(ZoneAuthorization auth, boolean replayed) {
            return new Response(auth.id(), auth.requestId(), auth.craneId(), auth.zoneId(),
                    auth.startTimeUtc(), auth.endTimeUtc(), auth.loadTonnes(), auth.status(),
                    auth.createdAtUtc(), auth.releasedAtUtc(), replayed);
        }
    }

    public record ReleaseRequest(String action) {
    }
}
