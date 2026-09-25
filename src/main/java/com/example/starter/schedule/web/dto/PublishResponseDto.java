package com.example.starter.schedule.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 按日发布结果；alreadyPublished=true 表示重复发布的幂等成功。
 */
public record PublishResponseDto(
        String stationCode,
        String dayKey,
        int versionNo,
        String status,
        Instant publishedAt,
        boolean alreadyPublished,
        List<EntryViewDto> entries) {
}
