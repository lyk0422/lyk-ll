package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 交接站信息响应。
 */
public record StationResponse(
        String stationCode,
        String stationName,
        String partyA,
        String partyB,
        Instant createdAt) {
}
