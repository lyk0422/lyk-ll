package com.example.rebate.domain;

/**
 * 疑点处理状态。
 */
public enum DoubtStatus {
    /** 校验产生疑点，等待企业补充说明。 */
    OPEN,
    /** 已提交说明，等待复核确认。 */
    EXPLAINED,
    /** 复核确认，疑点解除。 */
    RESOLVED
}
