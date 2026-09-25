package com.example.starter.roaming.web.dto;

import java.time.Instant;

/**
 * 会话结束响应；cached=true 表示会话此前已结束，本次未重复结束。
 */
public record StopResponse(
        String requestId,
        boolean cached,
        String sessionNo,
        String status,
        Instant startAt,
        Instant endAt,
        long totalEnergyWh,
        int lastMeterSeq) {
}
