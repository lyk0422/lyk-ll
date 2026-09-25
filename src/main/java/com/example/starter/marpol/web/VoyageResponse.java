package com.example.starter.marpol.web;

import java.time.Instant;

/**
 * 航次响应视图。
 */
public record VoyageResponse(
        String shipId,
        String voyageNo,
        String status,
        Instant closedAt) {
}
