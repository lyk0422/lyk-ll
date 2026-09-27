package com.example.starter.radio.domain;

/**
 * 给药时刻变更候选单状态：PENDING 待双人复核；APPROVED 双人复核完成已生效；
 * REJECTED 因分装已先完成发药而被拒；CANCELLED 被作废。除 PENDING 外均为终局。
 */
public enum DoseChangeStatus {

    /** 候选已提交，等待两名不同人员复核；候选期间不修改有效分装。 */
    PENDING,

    /** 两名不同人员复核完成，已在同事务内替换有效版本。 */
    APPROVED,

    /** 候选待决期间发药先提交成功，变更被拒绝；已发药历史不重开。 */
    REJECTED,

    /** 待决期间被作废。 */
    CANCELLED
}
