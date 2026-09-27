package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次拓扑视图：成员按申请编号稳定升序，依赖边按 (from,to) 升序。
 */
public record BatchView(
        String batchId,
        LocalDateTime publishedAt,
        List<BatchMemberView> members,
        List<BatchEdgeView> edges
) {
}
