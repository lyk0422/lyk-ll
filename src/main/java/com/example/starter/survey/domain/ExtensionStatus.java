package com.example.starter.survey.domain;

/**
 * 年度检验展期审批状态。
 */
public enum ExtensionStatus {
    /** 展期已申请，待批准。 */
    PENDING,
    /** 展期已批准，原证书有效期已在同一事务内更新。 */
    APPROVED,
    /** 展期申请被驳回。 */
    REJECTED
}
