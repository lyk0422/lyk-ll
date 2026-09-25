package com.example.starter.cems.web.dto;

import java.time.LocalDateTime;

/**
 * 连续数据缺失督办视图；仅当连续缺失严格超过 2 小时才生成，恰 2 小时不生成。
 */
public record NoticeView(
        Long id,
        String outfallCode,
        LocalDateTime gapStart,
        LocalDateTime gapEnd,
        int missingHours,
        String status,
        LocalDateTime createdAt) {
}
