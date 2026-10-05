package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 批次视图：发布时冻结的成员、依赖与窗口快照。
 */
public record BatchView(
        String batchId,
        String batchName,
        String status,
        Integer version,
        List<BatchMemberView> members,
        List<BatchEdgeView> edges
) {
}
