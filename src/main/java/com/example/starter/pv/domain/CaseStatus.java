package com.example.starter.pv.domain;

/**
 * 个例生命周期状态。
 */
public enum CaseStatus {

    /** 已初报（活动，可继续并入随访信息、可被递交）。 */
    REPORTED,
    /** 已关闭（终态，不得重开；新信息须新建关联个例并引用原编号）。 */
    CLOSED
}
