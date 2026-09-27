package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接申请视图。
 *
 * @param impactSystems 影响分析命中的业务系统编码；null 表示尚未分析
 * @param version       业务版本号（从 1 开始，每次成功变更 +1）
 * @param openBatchId   当前绑定的未结束批次 ID；null 表示未绑定
 * @param blockedSources 首个阻断来源集合（依赖阻断时的直接失败前置），按编号排序；空集合表示无阻断
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
        List<StepView> steps,
        long version,
        String openBatchId,
        List<String> blockedSources
) {
}
