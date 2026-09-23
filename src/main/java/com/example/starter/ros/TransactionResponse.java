package com.example.starter.ros;

import java.util.Map;

/**
 * 事务式热更新的响应体。
 *
 * @param transactionId 事务 ID（与请求一致；幂等重放时为原事务 ID）
 * @param status        COMMITTED 或 ROLLED_BACK
 * @param nodes         各目标节点的逐个结果
 * @param eventCursor   提交成功时写入的事务事件游标；回滚时为 null
 * @param replayed      是否为同键同参的幂等重放结果
 */
public record TransactionResponse(
        String transactionId,
        TransactionStatus status,
        Map<String, NodeOutcome> nodes,
        Long eventCursor,
        boolean replayed) {
}
