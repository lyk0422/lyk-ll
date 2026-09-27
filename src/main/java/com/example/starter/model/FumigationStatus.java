package com.example.starter.model;

/**
 * 熏蒸单状态：申请待批准→两名不同审批人批准→熏蒸中→散气中→已解除（终态）。
 */
public enum FumigationStatus {
    /** 待批准：已申请，需两名不同审批人共同批准 */
    PENDING_APPROVAL,
    /** 已批准：可开始熏蒸 */
    APPROVED,
    /** 熏蒸中：已原子锁仓并冻结方案版本，禁止人员进入与普通通风 */
    FUMIGATING,
    /** 散气中：已绑定一台空闲风机，可上报合成气体读数 */
    AERATING,
    /** 已解除（终态）：仓锁与风机已释放，此后读数只记迟到 */
    RELEASED
}
