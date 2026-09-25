package com.example.starter.pension.domain;

/**
 * 缴费类型。
 */
public enum ContributionType {

    /** 个人缴费，始终 100% 归属。 */
    PERSONAL,
    /** 企业缴费，按司龄阶梯归属。 */
    ENTERPRISE
}
