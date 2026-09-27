package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 拓扑节点视图：按申请编号稳定排序，标注该成员当前能否启动及等待的前置。
 *
 * @param directPrerequisites 该节点全部直接前置编号（冻结边），按编号排序
 * @param ready               是否满足启动门禁（批次内、APPROVED、全部直接前置 SEALED）
 * @param waitingOn           尚未 SEALED 的直接前置编号，按编号排序；空集合表示门禁满足
 * @param blockedSources      首个阻断来源集合，按编号排序；空集合表示未阻断
 */
public record TopologyNodeView(
        String requestId,
        String status,
        List<String> directPrerequisites,
        boolean ready,
        List<String> waitingOn,
        List<String> blockedSources
) {
}
