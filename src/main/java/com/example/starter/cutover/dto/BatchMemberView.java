package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次成员视图（拓扑查询按申请编号稳定升序）。
 *
 * @param requestVersion 发布时冻结的申请版本快照
 * @param blockSources   首个阻断来源集合（升序）；空表表示未阻断
 */
public record BatchMemberView(
        String requestId,
        Integer requestVersion,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        String memberStatus,
        List<String> blockSources
) {
}
