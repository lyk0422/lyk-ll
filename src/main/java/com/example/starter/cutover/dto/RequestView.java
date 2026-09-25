package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接申请视图。
 *
 * @param impactSystems 影响分析命中的业务系统编码；null 表示尚未分析
 */
public record RequestView(
        String requestId,
        String title,
        String deviceCode,
        String priority,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        String status,
        List<String> impactSystems,
        String rejectReason,
        String rollbackCause,
        List<StepView> steps
) {
}
