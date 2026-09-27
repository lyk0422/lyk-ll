package com.example.starter.radio.domain;

/**
 * 变更候选单状态机：
 * PENDING 待双人复核 → APPROVED 已生效（两名不同人员复核通过，同事务替换有效分装）；
 * PENDING 也可由提交人主动作废进入 VOID；
 * 原版本在候选 pending 期间抢先发药时，候选变为 SUPERSEDED（终态）。
 * 候选计算超偏差或库存校验失败直接 REJECTED（终态），不扣减母液。
 */
public enum ChangeOrderStatus {

    /** 候选已提交且计算合法，等待两名不同人员复核。 */
    PENDING,

    /** 双人复核通过并已同事务替换有效分装的给药时刻与计算版本，终态。 */
    APPROVED,

    /** 主动作废，终态。 */
    VOID,

    /** 原版本已先行发药，候选失效，终态。 */
    SUPERSEDED,

    /** 候选活度超 ±10% 偏差或库存校验失败，终态，不扣减母液。 */
    REJECTED
}
