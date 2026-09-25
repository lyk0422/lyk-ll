package com.example.starter.pension.domain;

/**
 * 成员在职状态。
 */
public enum EmploymentStatus {
    /** 在职（不可发起年金支付）。 */
    ACTIVE,
    /** 已离职，可发起支付。 */
    RESIGNED,
    /** 已退休，可发起支付。 */
    RETIRED
}
