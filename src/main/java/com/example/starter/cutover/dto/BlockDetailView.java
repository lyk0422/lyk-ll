package com.example.starter.cutover.dto;

import java.time.LocalDateTime;

/**
 * 依赖阻断明细视图（按被阻断申请编号、来源申请编号稳定排序）。
 *
 * @param cause 来源触发原因：ROLLBACK 回退 / WINDOW_EXPIRED 窗口超期 / DEPENDENCY_BLOCKED 阻断传导
 */
public record BlockDetailView(
        String batchId,
        String requestId,
        String sourceRequestId,
        String cause,
        LocalDateTime createdAt
) {
}
