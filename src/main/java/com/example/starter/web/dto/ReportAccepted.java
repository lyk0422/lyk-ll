package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 执行回报受理结果。deduplicated=true 表示命中幂等键或机组＋时刻去重；
 * late=true 表示封存/作废后的迟到回报，仅记录事件。
 */
public record ReportAccepted(
        long reportId,
        long commandId,
        long turbineId,
        LocalDateTime reportTime,
        int actualKw,
        boolean late,
        boolean deduplicated,
        boolean alarmRaised,
        boolean reallocated) {
}
