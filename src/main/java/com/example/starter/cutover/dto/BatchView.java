package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接批次视图：成员与依赖边均按申请编号稳定排序，发布即冻结。
 *
 * @param status      PUBLISHED 未结束 / CLOSED 已结束（全部成员终态）
 * @param version     批次版本号，从 1 开始，成功变更加 1
 * @param publishedAt 发布精确时刻，Asia/Shanghai
 */
public record BatchView(
        String batchId,
        String status,
        Integer version,
        LocalDateTime publishedAt,
        List<BatchMemberView> members,
        List<BatchEdgeView> edges
) {
}
