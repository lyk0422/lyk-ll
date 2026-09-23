package com.example.starter.rosconfig.dto;

import java.util.List;
import java.util.Map;

/**
 * 热更新事务的响应体；提交失败时 committed=false，数据库值、节点应用值均保持原状。
 *
 * @param transactionId 幂等事务键
 * @param committed     是否原子提交成功
 * @param eventCursor   提交成功后写入的唯一事务事件游标；重复事务返回首次结果；未提交为 null
 * @param versions      提交成功后各节点的新版本
 * @param results       各节点的逐项结果
 */
public record TransactionResponse(
        String transactionId,
        boolean committed,
        Long eventCursor,
        Map<String, Long> versions,
        List<NodeResult> results
) {
}
