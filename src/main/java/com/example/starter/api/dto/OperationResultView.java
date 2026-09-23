package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 状态操作结果。复用首次成功结果时 {@code replayed=true}，且不会追加事件。
 */
public record OperationResultView(
        String requestId,
        String operation,
        String status,
        int version,
        boolean replayed,
        List<ConflictView> preemptedConflicts,
        List<String> preemptedRequestIds,
        Instant businessTime
) {
}
