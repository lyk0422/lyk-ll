package com.example.starter.feed.domain;

/**
 * 出栏申请台账状态。EXECUTED（已核销出栏）为终态。
 */
public enum SlaughterApplicationStatus {
    /** 已批准预约：申请时刻在未来，批次保持饲养中，可被休药期重算自动撤销。 */
    SCHEDULED,
    /** 已核销出栏（终态），不可撤销。 */
    EXECUTED,
    /** 休药期内拒绝。 */
    REJECTED,
    /** 已批准预约与新最早出栏日冲突，被治疗直报重算自动撤销。 */
    REVOKED
}
