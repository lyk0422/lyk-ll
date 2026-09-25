package com.example.starter.pv;

/**
 * 个例状态：获知→初报→随访→关闭，关闭为终态。
 */
public enum CaseStatus {

    /** 已提交初报，尚无随访。 */
    INITIAL_SUBMITTED,

    /** 已提交至少一次随访。 */
    FOLLOW_UP_SUBMITTED,

    /** 已关闭（终态，不可重开）。 */
    CLOSED
}
