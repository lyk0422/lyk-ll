package com.example.starter.lc;

/**
 * 交单批次状态。
 */
public enum SubmissionStatus {

    /** 审单中：拒付通知期尚未结束。 */
    UNDER_REVIEW,

    /** 已拒付：银行在截止时间前发出了一次性列明全部不符点的拒付通知，单据状态锁定。 */
    REFUSED,

    /** 视为接受：未在 5 个银行工作日内发出有效拒付通知，自动接受。 */
    DEEMED_ACCEPTED,

    /** 已接受：拒付后申请人就全部不符点声明放弃，银行接受单据。 */
    ACCEPTED,

    /** 已付款：终态，额度已核减。 */
    PAID
}
