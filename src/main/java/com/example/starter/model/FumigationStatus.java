package com.example.starter.model;

/**
 * 熏蒸单状态：申请待双审批 → 双审批通过 → 熏蒸中（锁仓、冻结方案）→ 散气中（占用风机）→ 已解除（终态）。
 */
public enum FumigationStatus {
    /** 已申请，等待两名不同审批人共同批准 */
    PENDING_APPROVAL,
    /** 两名审批人均已批准，等待实际开始 */
    APPROVED,
    /** 熏蒸执行中：仓已锁定、方案版本已冻结，禁止人员进入与普通通风 */
    FUMIGATING,
    /** 散气中：占用一台风机，持续上报合成气体读数 */
    AERATING,
    /** 已解除（终态）：仓锁与风机已释放；此后读数只记迟到，不改冻结证据 */
    RELEASED
}
