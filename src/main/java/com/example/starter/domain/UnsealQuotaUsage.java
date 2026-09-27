package com.example.starter.domain;

import java.time.Instant;

/**
 * 解封额度消费轨迹：每次受控调节消费一次额度，同幂等键不重复消费。
 *
 * @param id        主键
 * @param unsealId  解封单 ID
 * @param taskId    消费额度产生的调节任务 ID
 * @param requestId 受控调节的客户端幂等键，全局唯一
 * @param usedAt    额度消费时刻（UTC）
 */
public record UnsealQuotaUsage(long id, long unsealId, long taskId, String requestId, Instant usedAt) {
}
