package com.example.starter.schedule.web.dto;

import java.time.Instant;

/**
 * 执行回报结果。duplicate=true 表示重复回报被去重，未新增记录。
 * late=true 表示漏报终态后的迟到回报：仅记录事件，漏报事实不变。
 */
public record ReportResponseDto(
        long windowId,
        String stationCode,
        Long reportId,
        String status,
        Instant reportedAt,
        boolean duplicate,
        boolean late) {
}
