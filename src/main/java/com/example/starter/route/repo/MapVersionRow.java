package com.example.starter.route.repo;

import java.time.OffsetDateTime;

/**
 * map_version 行记录。
 */
public record MapVersionRow(
        long id,
        String version,
        String status,
        OffsetDateTime publishedAt,
        OffsetDateTime deprecatedAt) {
}
