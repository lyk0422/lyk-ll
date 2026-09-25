package com.example.starter.domain;

/**
 * 航次状态：出港申报 → 进港报告 → 配额核销 → 结案，CLOSED 为终态。
 */
public enum VoyageStatus {

    /** 已出港（申报受理，允许提交渔捞日志）。 */
    DEPARTED,

    /** 已进港（卸渔报告受理，允许配额核销）。 */
    ARRIVED,

    /** 已核销（配额余额已扣减，允许结案）。 */
    SETTLED,

    /** 已结案（终态，不再接受业务变更，迟到日志仅记事件并转审查）。 */
    CLOSED
}
