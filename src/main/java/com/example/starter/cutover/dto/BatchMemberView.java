package com.example.starter.cutover.dto;

import java.time.LocalDateTime;

/**
 * 批次成员视图。
 *
 * @param windowStart 发布冻结的窗口开始快照（Asia/Shanghai）
 * @param windowEnd   发布冻结的窗口结束快照（Asia/Shanghai）
 * @param blockedBy   首个阻断来源根申请编号；null 表示未阻断
 */
public record BatchMemberView(
        String requestId,
        Integer expectedVersion,
        Integer currentVersion,
        String status,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        String blockedBy
) {
}
