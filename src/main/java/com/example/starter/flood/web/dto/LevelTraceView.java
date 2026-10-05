package com.example.starter.flood.web.dto;

import java.util.List;

/**
 * 处置链预警级别轨迹视图：当前级别与全部变更事件。
 */
public record LevelTraceView(
        String warningNo,
        String currentLevel,
        List<LevelEventView> events) {
}
