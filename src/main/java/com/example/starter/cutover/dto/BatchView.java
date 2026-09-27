package com.example.starter.cutover.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次视图：发布冻结的成员、依赖、窗口快照，以及成员实时状态与阻断明细。
 *
 * @param frozenAt      发布冻结的精确时刻
 * @param status        OPEN 未结束 / CLOSED 已结束
 * @param members       成员快照，按申请编号稳定排序
 * @param edges         依赖快照，按 (from,to) 稳定排序
 * @param memberStates  成员实时状态（申请编号、当前状态、版本、阻断来源集合）
 * @param blocks        依赖阻断明细
 */
public record BatchView(
        String batchId,
        LocalDateTime frozenAt,
        String status,
        List<BatchMemberView> members,
        List<BatchEdgeView> edges,
        List<BatchMemberStateView> memberStates,
        List<BlockView> blocks
) {
}
