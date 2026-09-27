package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 批次成员实时状态视图（按申请编号稳定排序）。
 *
 * @param version        申请当前业务版本
 * @param status         申请当前状态
 * @param blockedSources 首个阻断来源集合（直接失败前置），按编号排序；空集合表示无阻断
 */
public record BatchMemberStateView(
        String requestId,
        long version,
        String status,
        List<String> blockedSources
) {
}
