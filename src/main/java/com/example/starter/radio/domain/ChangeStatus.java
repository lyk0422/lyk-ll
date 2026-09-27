package com.example.starter.radio.domain;

/**
 * 给药时刻变更单状态：PENDING 候选待双人复核 → APPROVED 已生效（终态）；
 * 候选偏差超限、与发药竞争失败等进入 REJECTED（终态）；人工放弃进入 VOID（终态）。
 */
public enum ChangeStatus {

    /** 候选已提交，等待两名不同人员复核，尚未影响有效分装。 */
    PENDING,

    /** 双人复核完成并生效：已同事务替换有效分装版本（终态）。 */
    APPROVED,

    /** 候选被作废，未生效（终态）。 */
    VOID,

    /** 候选因偏差超限、母液校验失败或发药竞争失败被拒绝（终态）。 */
    REJECTED
}
