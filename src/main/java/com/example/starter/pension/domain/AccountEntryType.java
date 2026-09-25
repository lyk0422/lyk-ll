package com.example.starter.pension.domain;

/**
 * 账户划转台账类型。
 */
public enum AccountEntryType {
    /** 向成员个人账户出款。 */
    MEMBER_PAYOUT,
    /** 未归属企业缴费划回企业账户。 */
    COMPANY_RETURN
}
