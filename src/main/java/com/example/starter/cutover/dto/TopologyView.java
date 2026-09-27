package com.example.starter.cutover.dto;

import java.util.List;

/**
 * 拓扑查询视图：按申请编号稳定排序的节点与有向边，并标注每个成员的执行门禁。
 *
 * @param nodes 节点（申请）列表，按申请编号升序
 * @param edges 有向前置边，按 (from,to) 升序
 */
public record TopologyView(
        String batchId,
        List<TopologyNodeView> nodes,
        List<BatchEdgeView> edges
) {
}
