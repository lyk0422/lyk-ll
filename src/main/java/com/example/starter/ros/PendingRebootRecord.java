package com.example.starter.ros;

import java.time.Instant;

/**
 * 静态参数的待重启配置记录。
 *
 * <p>静态参数不能通过热更新改变；创建该记录仅表示“下次重启后生效”，
 * 读取已生效参数时不会合并该值。
 *
 * @param id            自增主键
 * @param nodeId        节点 ID
 * @param paramKey      静态参数名
 * @param newValue      待重启后应用的新值（文本表示）
 * @param transactionId 创建该待重启配置的请求 ID（幂等键）
 * @param createdAt     创建时间（UTC）
 */
public record PendingRebootRecord(
        long id,
        String nodeId,
        String paramKey,
        String newValue,
        String transactionId,
        Instant createdAt) {
}
