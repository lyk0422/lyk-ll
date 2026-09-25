package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 航行通告视图。
 */
public record NotamView(
        long id,
        long closureEventId,
        String notamType,
        String content,
        Instant publishedAt) {
}
