package com.example.starter.domain;

/**
 * 引航计划状态机：申请 → 编排 → 确认 → 执行 → 核销；执行前可取消。
 */
public enum PlanStatus {
    /** 已申请，尚未编排引航员/拖轮/潮水窗口。 */
    APPLIED,
    /** 已编排：绑定引航员、拖轮组合与潮水窗口，尚未锁定。 */
    SCHEDULED,
    /** 已确认：引航员与拖轮已在同一事务内锁定。 */
    CONFIRMED,
    /** 执行中。 */
    EXECUTING,
    /** 已核销（终态）。 */
    SETTLED,
    /** 已取消（终态）。 */
    CANCELLED
}
