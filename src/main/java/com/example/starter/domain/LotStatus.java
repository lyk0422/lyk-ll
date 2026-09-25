package com.example.starter.domain;

/**
 * 批次流转状态。
 */
public enum LotStatus {

    /** 已申报，田间检验与室内检测尚未齐回。 */
    DECLARED,
    /** 待定：一项不合格而另一项尚未回，或结论尚未形成。 */
    PENDING,
    /** 首检不合格，可在窗口内申请复检。 */
    UNQUALIFIED,
    /** 复检已受理，复检（第二轮）检验尚未齐回。 */
    RECHECKING,
    /** 分级合格，待发证。 */
    QUALIFIED,
    /** 已发证。 */
    CERTIFIED,
    /** 已注销（终态）。 */
    CANCELLED
}
