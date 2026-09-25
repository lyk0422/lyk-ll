package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 调节效果评估记录，调节稳定满 4 小时后生成。
 *
 * @param id          主键
 * @param taskId      被评估的调节任务 ID
 * @param requestId   客户端幂等键，全局唯一
 * @param fingerprint 幂等指纹：requestId 与全部业务字段的 SHA-256
 * @param beforeDiff  调节前站内回水温差（最高-最低），单位 ℃
 * @param afterDiff   评估时站内回水温差（最高-最低），单位 ℃
 * @param balanced    评估时是否平衡：温差不超过 2℃（恰 2℃ 合格）
 * @param detail      前后温度分布对比快照（JSON），可由温度记录复算
 * @param createdAt   评估生成时间（UTC）
 */
public record Assessment(long id, long taskId, String requestId, String fingerprint,
                         BigDecimal beforeDiff, BigDecimal afterDiff, boolean balanced,
                         String detail, Instant createdAt) {
}
