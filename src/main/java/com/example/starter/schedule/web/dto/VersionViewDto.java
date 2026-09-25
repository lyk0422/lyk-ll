package com.example.starter.schedule.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 同站某日的版本视图，旧版本可查。
 */
public record VersionViewDto(
        int versionNo,
        String status,
        Instant publishedAt,
        List<EntryViewDto> entries) {
}
