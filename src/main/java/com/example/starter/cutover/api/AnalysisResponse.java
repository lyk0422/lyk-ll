package com.example.starter.cutover.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 影响分析视图：列出全部关联业务与同窗口已批准/执行中的冲突变更。
 */
public record AnalysisResponse(
        Long id,
        String status,
        List<String> affectedBusinesses,
        List<ConflictView> overlappingChanges
) {

    /**
     * 冲突变更视图；higherPriority=true 表示该变更会在审批时抢占拒绝本申请。
     */
    public record ConflictView(
            Long applicationId,
            String requestId,
            String priority,
            String status,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            boolean higherPriority
    ) {
    }
}
