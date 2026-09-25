package com.example.starter.atm.domain;

/**
 * 计划事件流水。封存为终态后到达的清点、设备确认或打卡数据只登记 late=true 的迟到事件，不改变状态。
 */
public record PlanEvent(
        Long id,
        long planId,
        String eventType,
        boolean late,
        String detail,
        long eventAt,
        long createdAt
) {
}
