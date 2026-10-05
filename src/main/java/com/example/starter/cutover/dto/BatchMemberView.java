package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次成员视图（拓扑查询按申请编号稳定排序）。
 *
 * @param requestVersion  发布时冻结的申请版本号
 * @param requestStatus   申请当前状态（实时）
 * @param blockingSources 首个阻断来源集合（按申请编号排序）；空列表表示未被阻断
 */
public record BatchMemberView(
        String requestId,
        Integer requestVersion,
        String requestStatus,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        List<String> blockingSources
) {
}
