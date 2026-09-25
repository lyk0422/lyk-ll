package com.example.starter.reinsurance.domain;

/**
 * 账单确认方。
 */
public enum Party {

    /** 分出公司（账单出具方，账单生成即视为分出方已确认）。 */
    CEDANT,

    /** 再保人（可确认或逐项异议）。 */
    REINSURER
}
