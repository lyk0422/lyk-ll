package com.example.starter.domain;

import java.time.Instant;

/**
 * 解封额度消费轨迹：每次受控调节消费一次额度时登记，与开度写入同事务。
 *
 * @param id             主键
 * @param permitId       解封单 ID
 * @param taskId         消费额度产生的调节任务 ID
 * @param valveVersion   消费后阀门开度版本
 * @param quotaUsedAfter 消费后已用额度
 * @param createdAt      消费时刻（UTC）
 */
public record QuotaEvent(long id, long permitId, long taskId, int valveVersion,
                         int quotaUsedAfter, Instant createdAt) {
}
