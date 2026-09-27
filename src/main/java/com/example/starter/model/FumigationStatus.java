package com.example.starter.model;

/**
 * 熏蒸作业单状态：申请→两名不同审批人批准→开始（锁仓并冻结方案版本）
 * →结束熏蒸转散气（绑定空闲风机）→两名不同复核人确认解除（终态）。
 */
public enum FumigationStatus {
    /** 待审批：已申请，需两名不同审批人批准 */
    PENDING_APPROVAL,
    /** 已批准：两名不同审批人均已批准，可开始 */
    APPROVED,
    /** 熏蒸中：已原子锁仓并冻结方案版本，禁止人员进入与普通通风 */
    FUMIGATING,
    /** 散气中：熏蒸结束，已绑定一台空闲风机，采集合成气体读数 */
    AERATING,
    /** 已解除（终态）：散气结束，仓锁与风机已释放，此后读数只记迟到 */
    RELEASED
}
