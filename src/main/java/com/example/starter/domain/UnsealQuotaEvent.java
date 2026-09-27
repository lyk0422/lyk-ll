package com.example.starter.domain;

import java.time.Instant;

/**
 * 解封额度轨迹事件：授予/消费/撤销/终结，与业务变更同事务写入。
 *
 * @param id        主键
 * @param orderId   所属解封单 ID
 * @param valveId   阀门 ID
 * @param eventType 事件类型：GRANT 授予 / CONSUME 消费 / REVOKE 撤销 / TERMINATE 复封终结
 * @param quotaUsed 事件发生后已用额度
 * @param taskId    消费事件对应的调节任务 ID；非消费事件为 null
 * @param detail    事件说明（合成数据）
 * @param createdAt 事件时间（UTC）
 */
public record UnsealQuotaEvent(long id, long orderId, long valveId, EventType eventType,
                               int quotaUsed, Long taskId, String detail, Instant createdAt) {

    /**
     * 额度轨迹事件类型。
     */
    public enum EventType {
        /** 授予：解封批准，额度生效。 */
        GRANT,
        /** 消费：受控调节成功，额度减一。 */
        CONSUME,
        /** 撤销：解封单被撤销，剩余额度作废。 */
        REVOKE,
        /** 复封终结：复封原子终结解封权限，剩余额度作废。 */
        TERMINATE
    }
}
