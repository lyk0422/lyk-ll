package com.example.starter.pension.domain;

/**
 * 缴费来源。
 */
public enum ContributionSource {
    /** 企业缴费：按司龄阶梯归属，未归属部分划回企业。 */
    EMPLOYER,
    /** 个人缴费：始终 100% 归属。 */
    EMPLOYEE
}
