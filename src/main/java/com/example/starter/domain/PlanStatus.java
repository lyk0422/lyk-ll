package com.example.starter.domain;

/**
 * 引航计划状态机：申请 -> 编排 -> 确认 -> 执行 -> 核销；申请/编排/确认阶段可取消。
 */
public enum PlanStatus {
    /** 已申请，尚未编排。 */
    APPLIED,
    /** 已编排，已绑定引航员、拖轮组合与潮水窗口。 */
    ARRANGED,
    /** 已确认，引航员与拖轮已同事务锁定。 */
    CONFIRMED,
    /** 执行中。 */
    EXECUTING,
    /** 已核销，资源锁定已释放。 */
    SETTLED,
    /** 已取消。 */
    CANCELLED
}
