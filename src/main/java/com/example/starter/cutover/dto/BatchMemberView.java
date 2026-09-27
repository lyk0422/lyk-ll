package com.example.starter.cutover.dto;

import java.time.LocalDateTime;

/**
 * 批次成员冻结视图（按申请编号稳定排序）。
 */
public record BatchMemberView(
        String requestId,
        long expectedVersion,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        int ord
) {
}
