package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接申请视图。
 *
 * @param impactSystems 影响分析命中的业务系统编码；null 表示尚未分析
 * @param version       乐观锁版本，从 1 开始，每次成功变更加 1
 * @param batchId       所属依赖批次 ID；null 表示未绑定批次
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
        Integer version,
        String batchId,
        List<StepView> steps
) {
}
