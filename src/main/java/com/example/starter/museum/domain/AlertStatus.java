package com.example.starter.museum.domain;

/**
 * 环境异常状态：
 * OPEN 连续 3 点越限已触发、待 24 小时内处置；
 * URGENT 满 24 小时未确认处置，升级紧急并冻结展柜新入藏；
 * AWAIT_REVIEW 环境已恢复，待借出借入双方复核；
 * RESOLVED 双方复核完成，合同恢复在展（终态）。
 */
public enum AlertStatus {
    OPEN,
    URGENT,
    AWAIT_REVIEW,
    RESOLVED
}
