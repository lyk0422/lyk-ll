package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 预警级别变更事件视图（留痕可查）。
 */
public record LevelEventView(
        String fromLevel,
        String toLevel,
        String reason,
        String rollingNo,
        LocalDateTime createdAt) {
}
